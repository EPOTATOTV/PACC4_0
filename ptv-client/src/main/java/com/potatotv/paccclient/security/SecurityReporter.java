package com.potatotv.paccclient.security;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.apm.ApmCollector;
import com.potatotv.paccclient.apm.ClientHealthMetrics;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 安全事件上报与远程证明调度。
 *
 * <p>每分钟跑一次「反调试 + 反注入 + 代码完整性」评估，把结论写入
 * {@link ClientHealthMetrics}（供 APM 上报），命中时通过 {@code events} 出口上报安全事件；
 * 每 30 分钟（启动后 60 秒首次）跑一次远程证明挑战-应答。</p>
 *
 * <p>两个出口都是函数式接口，因此本类不认识 HTTP：事件走 {@code events}，
 * 远程证明的两次 POST 走 {@link AttestationTransport}（由 {@code OpsClient} 侧实现）。</p>
 *
 * <p>去重：同一个 {@code type|detail} 10 分钟内不重复发送，避免持续命中时把服务端刷屏。</p>
 */
public final class SecurityReporter {

    /** 批量安全事件出口。 */
    @FunctionalInterface
    public interface BatchSink {
        void send(String jsonBody);
    }

    /** 远程证明挑战。 */
    public record Challenge(String challengeId, String nonce) {
    }

    /** 远程证明传输：发起挑战与提交应答（实现方负责 HTTP 与 JSON 编码）。 */
    public interface AttestationTransport {
        Challenge challenge(String codeHash, String configHash);

        String respond(String challengeId, String nonce, String codeHash, String configHash,
                       Map<String, String> runtimeState, String signature, long elapsedMs);
    }

    private static final long DEDUP_WINDOW_MILLIS = 600_000L;
    private static final long ATTESTATION_PERIOD_SECONDS = 1800;

    private final BatchSink events;
    private final BatchSink attestation;
    private final AntiDebugService antiDebug = new AntiDebugService();
    private final HookDetector hookDetector = new HookDetector();
    private final Map<String, Long> lastSentAt = new HashMap<>();

    private volatile CodeIntegrityService integrity = new CodeIntegrityService();
    private volatile AttestationTransport transport;
    private volatile String signSecret = "";
    private volatile String configHash = "";
    private volatile String platform = ApmCollector.platform();
    private volatile String pinnedJarHash = "";
    private ScheduledExecutorService executor;

    /**
     * @param events      安全事件出口
     * @param attestation 证明相关事件出口（服务端只有 security/events 时通常与 events 相同）
     */
    public SecurityReporter(BatchSink events, BatchSink attestation) {
        this.events = events;
        this.attestation = attestation != null ? attestation : events;
    }

    /** 注入代码完整性服务（缺省自建一个）。 */
    public void setIntegrityService(CodeIntegrityService service) {
        if (service != null) this.integrity = service;
    }

    /** 注入远程证明传输；未注入时跳过证明流程。 */
    public void setAttestationTransport(AttestationTransport transport) {
        this.transport = transport;
    }

    /** 注入 WSS 签名密钥：证明应答用它做 HMAC-SHA256。 */
    public void setSignSecret(String secret) {
        this.signSecret = secret == null ? "" : secret;
    }

    /** 客户端配置指纹（与 APM 上报同一口径）。 */
    public void setConfigHash(String hash) {
        this.configHash = hash == null ? "" : hash;
    }

    /** 平台标识，缺省与 APM 上报一致。 */
    public void setPlatform(String platform) {
        if (platform != null && !platform.isBlank()) this.platform = platform;
    }

    /** 固定期望的 jar 哈希；为空表示不固定（pinning 关闭）。 */
    public void setPinnedJarHash(String hash) {
        this.pinnedJarHash = hash == null ? "" : hash;
    }

    /** 启动调度：评估 + 证明。重复调用无副作用。 */
    public synchronized void start(long intervalSeconds) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-security").unstarted(r));
        long period = Math.max(30, intervalSeconds);
        executor.scheduleWithFixedDelay(this::assessQuietly, 2, period, TimeUnit.SECONDS);
        // 启动后 60 秒错峰首次证明，避免与启动期的模型下载/指纹上报抢带宽
        executor.scheduleWithFixedDelay(this::attestQuietly, 60, ATTESTATION_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 立即执行一次评估（供测试/手动触发）。 */
    public void assessNow() {
        assessQuietly();
    }

    /** 立即执行一次远程证明（供测试/手动触发）。 */
    public void attestNow() {
        attestQuietly();
    }

    private void assessQuietly() {
        try {
            AntiDebugService.Assessment debug = antiDebug.assess();
            HookDetector.Assessment hook = hookDetector.check();
            CodeIntegrityService.Assessment code = integrity.verify(pinnedJarHash);

            ClientHealthMetrics.SINK.setAntidebugState(debug.detected() ? 1 : 0);
            ClientHealthMetrics.SINK.setAntihookState(HookDetector.suspicious(hook.score()) ? 1 : 0);
            ClientHealthMetrics.SINK.setIntegrityState(code.match() ? 0 : 1);

            reportEvents(debug, hook, code);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[PTV-Security] 安全评估异常（跳过本轮）: " + e.getMessage());
        }
    }

    /** 依据三项评估结论组装事件；命中才发送，全部正常则不产生网络流量。 */
    private int reportEvents(AntiDebugService.Assessment debug, HookDetector.Assessment hook,
                             CodeIntegrityService.Assessment code) {
        List<String> events = new ArrayList<>();
        long now = System.currentTimeMillis();

        if (debug.detected()) {
            String detail = debug.findings().isEmpty() ? "heuristics" : debug.findings().get(0);
            addEvent(events, "debugger_detected", "CRITICAL", detail,
                    evidence(debug.score(), debug.findings()), now);
        }

        List<String> envFindings = new ArrayList<>();
        List<String> otherFindings = new ArrayList<>();
        for (HookDetector.Finding finding : hook.findings()) {
            String label = finding.kind() + ":" + finding.target();
            if ("env_injection".equals(finding.kind())) envFindings.add(label);
            else otherFindings.add(label);
        }
        if (!envFindings.isEmpty()) {
            addEvent(events, "hook_detected", "HIGH", envFindings.get(0),
                    evidence(hook.score(), envFindings), now);
        }
        if (!otherFindings.isEmpty()) {
            addEvent(events, "hook_detected", "CRITICAL", otherFindings.get(0),
                    evidence(hook.score(), otherFindings), now);
        }

        if (!code.match()) {
            addEvent(events, "integrity_violation", "CRITICAL", "jar_hash_mismatch",
                    evidence(0, List.of("actual=" + shortHash(code.actualHash()),
                            "expected=" + shortHash(code.expectedHash()))), now);
        }

        if (events.isEmpty()) return 0;
        try {
            this.events.send(encodeEvents(events));
        } catch (RuntimeException e) {
            System.err.println("[PTV-Security] 安全事件上报失败: " + e.getMessage());
        }
        return events.size();
    }

    /** 加入事件列表，命中 10 分钟去重窗口的条目会被丢弃。 */
    private void addEvent(List<String> target, String type, String level, String detail,
                          String evidenceJson, long occurredAt) {
        String key = type + "|" + detail;
        synchronized (lastSentAt) {
            Long last = lastSentAt.get(key);
            if (last != null && (occurredAt - last) < DEDUP_WINDOW_MILLIS) return;
            lastSentAt.put(key, occurredAt);
        }
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"type\":").append(Json.encode(type))
                .append(",\"level\":").append(Json.encode(level))
                .append(",\"detail\":").append(Json.encode(detail))
                .append(",\"evidence\":").append(evidenceJson)
                .append(",\"occurred_at\":").append(occurredAt).append('}');
        target.add(sb.toString());
    }

    /** 组装证据对象：{@code {"score":55,"findings":["jdwp_agent"]}}。 */
    private static String evidence(int score, List<String> findings) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"score\":").append(score).append(",\"findings\":[");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.encode(findings.get(i)));
        }
        return sb.append("]}").toString();
    }

    private String encodeEvents(List<String> encodedEvents) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"client_version\":").append(Json.encode(ApmCollector.CLIENT_VERSION))
                .append(",\"platform\":").append(Json.encode(platform))
                .append(",\"events\":[");
        for (int i = 0; i < encodedEvents.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(encodedEvents.get(i));
        }
        return sb.append("]}").toString();
    }

    private void attestQuietly() {
        try {
            runAttestation();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[PTV-Security] 远程证明异常: " + e.getMessage());
        }
    }

    /**
     * 远程证明：取哈希 → 领挑战 → 计时 → 规范化载荷 HMAC-SHA256 签名 → 提交应答。
     * 失败统一记一条 {@code attestation_fail} 事件。
     */
    private void runAttestation() {
        AttestationTransport current = transport;
        if (current == null) return;
        String secret = signSecret;
        if (secret.isBlank()) {
            reportAttestationFail("sign_secret_missing");
            return;
        }
        long started = System.nanoTime();
        String codeHash = integrity.jarHash();
        try {
            Challenge challenge = current.challenge(codeHash, configHash);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (challenge == null || challenge.challengeId() == null || challenge.challengeId().isEmpty()) {
                reportAttestationFail("challenge_unavailable");
                return;
            }
            String payload = challenge.challengeId() + "|" + challenge.nonce() + "|" + codeHash
                    + "|" + configHash + "|" + elapsedMs;
            String signature = hmacSha256Hex(secret, payload);
            String body = current.respond(challenge.challengeId(), challenge.nonce(), codeHash,
                    configHash, integrity.runtimeState(), signature, elapsedMs);
            if (!accepted(body)) {
                reportAttestationFail("attestation_rejected");
            }
        } catch (Exception e) {
            reportAttestationFail("attestation_error");
        }
    }

    private void reportAttestationFail(String detail) {
        List<String> events = new ArrayList<>(1);
        addEvent(events, "attestation_fail", "HIGH", detail, "{}", System.currentTimeMillis());
        if (events.isEmpty()) return;
        try {
            attestation.send(encodeEvents(events));
        } catch (RuntimeException e) {
            System.err.println("[PTV-Security] 证明失败事件上报异常: " + e.getMessage());
        }
    }

    /** 判断服务端应答是否通过；缺失明确判定字段时以「HTTP 2xx 即通过」为准。 */
    private static boolean accepted(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            Map<String, Object> obj = Json.decodeObject(body);
            for (String key : new String[]{"verified", "ok", "passed", "match"}) {
                Object value = obj.get(key);
                if (value instanceof Boolean b) return b;
                if (value instanceof String s) {
                    return "true".equalsIgnoreCase(s) || "ok".equalsIgnoreCase(s);
                }
            }
            Object status = obj.get("status");
            if (status instanceof String s) {
                return "ok".equalsIgnoreCase(s) || "verified".equalsIgnoreCase(s) || "pass".equalsIgnoreCase(s);
            }
            return true;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 规范化载荷的 HMAC-SHA256 十六进制。 */
    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isEmpty()) return "";
        return hash.length() <= 16 ? hash : hash.substring(0, 16);
    }

    /**
     * 组装 attestation/respond 的请求体：{@code runtime_state} 必须是真正的嵌套 JSON 对象，
     * 不能用 {@code Json.encode(Map)}（后者会把内层 Map 字符串化）。
     */
    public static String encodeRespondBody(String challengeId, String nonce, String codeHash,
                                           String configHash, Map<String, String> runtimeState,
                                           String signature, long elapsedMs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"challenge_id\":").append(Json.encode(challengeId))
                .append(",\"nonce\":").append(Json.encode(nonce))
                .append(",\"code_hash\":").append(Json.encode(codeHash))
                .append(",\"config_hash\":").append(Json.encode(configHash))
                .append(",\"runtime_state\":").append(encodeRuntimeState(runtimeState))
                .append(",\"signature\":").append(Json.encode(signature))
                .append(",\"elapsed_ms\":").append(elapsedMs)
                .append('}');
        return sb.toString();
    }

    /** 运行时状态对象编码（值为字符串）。 */
    public static String encodeRuntimeState(Map<String, String> state) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        if (state != null) {
            for (Map.Entry<String, String> entry : state.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(Json.encode(entry.getKey())).append(':').append(Json.encode(entry.getValue()));
            }
        }
        return sb.append('}').toString();
    }
}