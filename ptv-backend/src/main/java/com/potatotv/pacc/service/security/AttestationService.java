package com.potatotv.pacc.service.security;

import com.potatotv.pacc.domain.AttestationRecord;
import com.potatotv.pacc.domain.KnownGoodHash;
import com.potatotv.pacc.repository.AttestationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v5.4 §3.6 客户端远程证明（anti-tamper）。
 *
 * <p>核心原则：服务端绝不采信客户端自述的安全状态。流程是服务端发随机 nonce → 客户端须在极短
 * 时延内回「代码段摘要 + 配置摘要 + 签名」→ 服务端独立核验 nonce 新鲜度、响应时延、HMAC 签名，
 * 以及按需对照已知good摘要表。任何一环不过都判 FAIL 并落库留痕。</p>
 *
 * <p>签名密钥复用 WSS 信封同一共享密钥 {@code pacc.security.wss-sign-secret}：端侧本就有这条密钥，
 * 无需为证明另开分发通道。密钥未配置时校验「失败关闭」（一律拒绝并只告警一次），绝不静默放行。</p>
 *
 * <p>待应答挑战存在进程内 {@code ConcurrentHashMap}：本部署单后端实例，且挑战有 TTL，
 * 天然只服务于短窗口内的应答；每次下发顺带裁剪过期项并把容量压在 10000 以内。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AttestationService {

    /** pending 上限，超过则按签发时间丢弃最旧的。 */
    private static final int MAX_PENDING = 10000;
    /** nonce 随机字节数（32 字节 = 256 位熵）。 */
    private static final int NONCE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AttestationRecordRepository records;
    private final KnownGoodHashService knownGoodHashes;

    /** WSS 共享签名密钥；空则校验一律失败。 */
    @Value("${pacc.security.wss-sign-secret:}")
    private String signSecret;

    /** 挑战有效期（服务端侧）。 */
    @Value("${pacc.attestation.ttl-ms:100000}")
    private long ttlMs;

    /** 客户端自述应答耗时上限。 */
    @Value("${pacc.attestation.max-elapsed-ms:500}")
    private long maxElapsedMs;

    /** 是否要求代码段摘要已在注册表中（默认关闭，便于先用再收紧）。 */
    @Value("${pacc.attestation.require-known-hash:false}")
    private boolean requireKnownHash;

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean secretWarned = new AtomicBoolean(false);

    /** 待应答挑战。 */
    private record Pending(String pteid, String platform, String clientVer, String codeHash,
                           String configHash, Instant issuedAt, String nonce) {
    }

    /** 下发的挑战。 */
    public record Challenge(String challengeId, String nonce, Instant issuedAt, Instant expiresAt, long ttlMs) {
    }

    /** 应答结论（status 取值固定为 PASS / FAIL）。 */
    public record Result(String status, String reason, long elapsedMs, String recordId) {
    }

    /** 签发一个随机挑战，并记住挑战时登记的代码段/配置摘要用于后续比对。 */
    public Challenge issue(String pteid, String platform, String clientVer, String codeHash, String configHash) {
        Instant now = Instant.now();
        prunePending(now);

        byte[] raw = new byte[NONCE_BYTES];
        RANDOM.nextBytes(raw);
        String nonce = HexFormat.of().formatHex(raw);
        String challengeId = UUID.randomUUID().toString();
        Instant expiresAt = now.plusMillis(Math.max(0L, ttlMs));
        pending.put(challengeId, new Pending(nz(pteid), nz(platform), nz(clientVer),
                nz(codeHash), nz(configHash), now, nonce));
        log.debug("签发远程证明挑战 challengeId={} pteid={} ttlMs={}", challengeId, pteid, ttlMs);
        return new Challenge(challengeId, nonce, now, expiresAt, ttlMs);
    }

    /**
     * 核验一次应答。无论成败都落一条 {@link AttestationRecord}。
     *
     * <p>核验顺序刻意从「便宜的本地判断」到「贵的签名/查库」，失败原因取第一条命中的，
     * 便于线上快速定位端侧到底坏在哪一环。</p>
     */
    public Result respond(String challengeId, String nonce, String codeHash, String configHash,
                          String runtimeState, String signature, long elapsedMs) {
        Instant now = Instant.now();
        Pending p = (challengeId == null || challengeId.isBlank()) ? null : pending.remove(challengeId);
        String cid = nz(challengeId);
        long safeElapsed = Math.max(0L, elapsedMs);

        if (p == null) {
            return outcome(cid, "", "", "", nz(nonce), nz(codeHash), nz(configHash), runtimeState,
                    false, "no_pending_challenge", safeElapsed, now, now);
        }

        Instant issuedAt = p.issuedAt();
        long serverElapsed = Math.max(0L, now.toEpochMilli() - issuedAt.toEpochMilli());
        if (serverElapsed > ttlMs) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "challenge_expired", safeElapsed, issuedAt, now);
        }
        if (elapsedMs < 0L || elapsedMs > maxElapsedMs) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "response_too_slow", safeElapsed, issuedAt, now);
        }
        if (!p.nonce().equals(nz(nonce))) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "nonce_mismatch", safeElapsed, issuedAt, now);
        }
        if (!p.codeHash().equalsIgnoreCase(nz(codeHash))) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "code_hash_changed", safeElapsed, issuedAt, now);
        }
        if (!signatureValid(cid, nonce, codeHash, configHash, elapsedMs, signature)) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "signature_invalid", safeElapsed, issuedAt, now);
        }
        if (requireKnownHash && !knownGoodHashes.isKnownGood(KnownGoodHash.Kind.CODE_SEGMENT, codeHash)) {
            return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                    runtimeState, false, "hash_not_registered", safeElapsed, issuedAt, now);
        }
        return outcome(cid, p.pteid(), p.platform(), p.clientVer(), nz(nonce), nz(codeHash), nz(configHash),
                runtimeState, true, "", safeElapsed, issuedAt, now);
    }

    /**
     * 证明记录列表 + 通过率。
     *
     * <p>通过率：不带 pteid 过滤时用仓储计数（全量口径，避免只算返回页）；带 pteid 时按返回集合计算，
     * 因为仓储没有按玩家+状态的口径，硬用全量会把单个玩家的通过率算成全局值。</p>
     */
    public Map<String, Object> list(String pteid, int limit) {
        int n = clamp(limit, 1, 200);
        boolean scoped = pteid != null && !pteid.isBlank();
        List<AttestationRecord> rows = scoped
                ? records.findTop100ByPteidOrderByCreatedAtDesc(pteid.trim())
                : records.findTop100ByOrderByCreatedAtDesc();

        List<Map<String, Object>> items = new ArrayList<>();
        long passInItems = 0L;
        for (AttestationRecord r : rows) {
            if (items.size() >= n) break;
            items.add(viewOf(r));
            if (r.getStatus() == AttestationRecord.Status.PASS) passInItems++;
        }

        long pass;
        long total;
        if (scoped) {
            pass = passInItems;
            total = items.size();
        } else {
            pass = records.countByStatus(AttestationRecord.Status.PASS);
            total = records.count();
        }
        double passRate = total <= 0L ? 0.0 : Math.round(pass * 10000.0 / total) / 10000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("pass_rate", passRate);
        out.put("total", items.size());
        return out;
    }

    /** 规范化签名载荷：与服务端复算、端侧实现、单测三方共用同一串。 */
    public static String canonicalSignaturePayload(String challengeId, String nonce, String codeHash,
                                                   String configHash, long elapsedMs) {
        return nz(challengeId) + "|" + nz(nonce) + "|" + nz(codeHash) + "|" + nz(configHash) + "|" + elapsedMs;
    }

    /** HMAC-SHA256 校验；密钥未配置或签名缺失一律失败（fail-closed），常量时间比较防时序侧信道。 */
    private boolean signatureValid(String challengeId, String nonce, String codeHash, String configHash,
                                   long elapsedMs, String signature) {
        if (signSecret == null || signSecret.isBlank()) {
            warnSecretMissing();
            return false;
        }
        if (signature == null || signature.isBlank()) return false;
        String expected = hmacHex(signSecret,
                canonicalSignaturePayload(challengeId, nonce, codeHash, configHash, elapsedMs));
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private void warnSecretMissing() {
        if (secretWarned.compareAndSet(false, true)) {
            log.warn("pacc.security.wss-sign-secret 未配置，远程证明签名校验一律拒绝（fail-closed）");
        }
    }

    /** 落库并组装结论。 */
    private Result outcome(String challengeId, String pteid, String platform, String clientVer,
                           String nonce, String codeHash, String configHash, String runtimeState,
                           boolean pass, String reason, long elapsedMs, Instant issuedAt, Instant now) {
        AttestationRecord rec = AttestationRecord.builder()
                .id(UUID.randomUUID().toString())
                .challengeId(challengeId)
                .pteid(pteid)
                .platform(platform)
                .clientVer(clientVer)
                .nonce(nonce)
                .codeHash(codeHash)
                .configHash(configHash)
                .runtimeState(runtimeState)
                .status(pass ? AttestationRecord.Status.PASS : AttestationRecord.Status.FAIL)
                .reason(pass ? "" : nz(reason))
                .elapsedMs(elapsedMs)
                .issuedAt(issuedAt)
                .createdAt(now)
                .build();
        try {
            records.save(rec);
        } catch (Exception e) {
            // 留痕失败不应把结论吞掉：客户端仍应拿到 PASS/FAIL 以驱动端侧行为
            log.error("远程证明记录落库失败 challengeId={} status={}", challengeId, rec.getStatus(), e);
        }
        if (!pass) {
            log.info("远程证明失败 pteid={} reason={} elapsedMs={}", pteid, reason, elapsedMs);
        }
        return new Result(pass ? "PASS" : "FAIL", pass ? "" : nz(reason), elapsedMs, rec.getId());
    }

    /** 单行 → 控制层视图。 */
    private Map<String, Object> viewOf(AttestationRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("pteid", nz(r.getPteid()));
        m.put("platform", nz(r.getPlatform()));
        m.put("client_version", nz(r.getClientVer()));
        m.put("status", r.getStatus() == null ? "" : r.getStatus().name());
        m.put("reason", nz(r.getReason()));
        m.put("elapsed_ms", r.getElapsedMs());
        m.put("code_hash", trim(r.getCodeHash()));
        m.put("config_hash", trim(r.getConfigHash()));
        m.put("issued_at", r.getIssuedAt() == null ? "" : r.getIssuedAt().toString());
        m.put("created_at", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
        return m;
    }

    /** 裁剪过期挑战；超过容量上限则按签发时间丢最旧的。 */
    private void prunePending(Instant now) {
        pending.entrySet().removeIf(e -> e.getValue().issuedAt().plusMillis(Math.max(0L, ttlMs)).isBefore(now));
        if (pending.size() <= MAX_PENDING) return;
        List<Map.Entry<String, Pending>> entries = new ArrayList<>(pending.entrySet());
        entries.sort(Comparator.comparing(e -> e.getValue().issuedAt()));
        int drop = pending.size() - MAX_PENDING;
        for (int i = 0; i < drop && i < entries.size(); i++) {
            pending.remove(entries.get(i).getKey());
        }
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}