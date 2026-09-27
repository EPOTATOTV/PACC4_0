package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import com.potatotv.paccclient.detection.samples.PositionSample;
import com.potatotv.paccclient.detection.samples.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 版（基岩版不可用）探针的客户端侧：轮询 {@code -javaagent} 注入到游戏进程的
 * {@code platform/java-agent}，把探针结论接进端侧检测链路。
 *
 * <p>两条数据用途：</p>
 * <ol>
 *   <li><b>识别结论</b>：{@code /findings} 里的 mod 验签、类装载审计、字节码完整性、异常移动等结论
 *       转成 {@link DetectionEvent} 上报 PTV；</li>
 *   <li><b>行为采样</b>：{@code runtime_sample} 结论里的坐标 / 鼠标增量 / 攻击键状态推进
 *       {@link BufferedInputSource}，成为战斗与移动特征（178 维）的真实数据源——这正是文档 §2.2.1
 *       「鼠标 Hook + 游戏内存只读采样」在 Java 版下的落地形式。</li>
 * </ol>
 *
 * <p>探针未启动（非 Java 版、未带 {@code -javaagent}）时 {@code /findings} 连不上，
 * 本类安静返回空结果，不影响既有检测；结论按 {@code signature+detail} 去重并带 TTL，
 * 避免探针 30 秒窗口内的同一条结论被反复上报。</p>
 */
public final class JavaAgentProbe {

    /** 探针回环服务默认地址（与 {@code ReportServer} 的 17020 端口一致）。 */
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:17020";
    /** 结论去重 TTL。 */
    private static final long SEEN_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_SEEN = 512;
    /** detail 明细长度上限（防止超长 JSON 进入上报载荷）。 */
    private static final int MAX_DETAIL = 2000;
    /** 单次轮询最多转换的结论数。 */
    private static final int MAX_ITEMS = 64;

    private final String endpoint;
    private final HttpClient http;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** 累计鼠标位移：探针给的是增量，轨迹分析要的是轨迹点。 */
    private double cumulativeX;
    private double cumulativeY;
    /** 上一拍攻击键状态，用于把「按下」转成一次点击事件。 */
    private boolean attackPressed;

    public JavaAgentProbe() {
        this(firstNonBlank(System.getProperty("pacc.agent.endpoint"),
                System.getenv("PACC_AGENT_ENDPOINT")));
    }

    public JavaAgentProbe(String endpoint) {
        String e = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.endpoint = e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    }

    /** 旧接口保留：外部已知可疑模块时的兜底判定（不再作为主路径）。 */
    public Optional<DetectionEvent> scanForMods(boolean suspiciousModuleLoaded) {
        if (suspiciousModuleLoaded) {
            return Optional.of(new DetectionEvent("java_mod", "critical", 96,
                    "javaw.exe", null, "injected-module", "java21"));
        }
        return Optional.empty();
    }

    /**
     * 轮询探针结论。
     *
     * @param source 行为采样落点（可为 {@code null}，此时只取识别结论）
     * @return 需要上报的端侧事件（去重后；探针不可达时为空表）
     */
    public List<DetectionEvent> poll(BufferedInputSource source) {
        Optional<String> body = fetch("/findings");
        if (body.isEmpty()) return List.of();
        List<DetectionEvent> events = new ArrayList<>();
        try {
            Object raw = Json.decodeObject(body.get()).get("findings");
            if (!(raw instanceof List<?> items)) return List.of();
            int n = 0;
            for (Object item : items) {
                if (++n > MAX_ITEMS) break;
                if (!(item instanceof Map<?, ?> m)) continue;
                String signature = str(m.get("signature"));
                String severity = str(m.get("severity"));
                String detail = clamp(str(m.get("detail")));
                if (signature.isEmpty()) continue;
                if ("runtime_sample".equals(signature)) {
                    if (source != null) feedSample(source, detail);
                    continue;
                }
                if (dup(signature, detail)) continue;
                severityEvent(signature, severity, detail).ifPresent(events::add);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return events;
    }

    /** 探针是否在线（{@code /health} 可读）。 */
    public boolean online() {
        return fetch("/health").isPresent();
    }

    // ------------------------------ 内部实现 ------------------------------

    private Optional<String> fetch(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + path))
                .GET().timeout(Duration.ofMillis(1200)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) return Optional.empty();
            return Optional.of(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 结论 → 事件：只有 medium 以上（risk &gt;= 60）才上报，info 级采样结论不产生事件。 */
    private static Optional<DetectionEvent> severityEvent(String signature, String severity, String detail) {
        int risk = switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 92;
            case "high" -> 80;
            case "medium" -> 62;
            case "low" -> 45;
            default -> 0;
        };
        if (risk < 60) return Optional.empty();
        String type = mapType(signature);
        String level = risk >= 90 ? "critical" : risk >= 80 ? "high" : "medium";
        return Optional.of(new DetectionEvent(type, level, risk,
                "javaw.exe", null, signature, "java21", detail.isEmpty() ? null : detail));
    }

    /** 探针结论前缀 → 端侧事件类型（保持既有命名，便于后端归族）。 */
    private static String mapType(String signature) {
        String s = signature.toLowerCase(Locale.ROOT);
        if (s.startsWith("mod") || s.contains("jar")) return "java_mod";
        if (s.startsWith("class_loader") || s.startsWith("classloader")) return "java_classloader";
        if (s.startsWith("bytecode") || s.contains("integrity")) return "bytecode_tamper";
        if (s.contains("movement")) return "abnormal_movement";
        if (s.startsWith("inject")) return "reflective_dll";
        return "java_agent";
    }

    private boolean dup(String signature, String detail) {
        long now = System.currentTimeMillis();
        seen.values().removeIf(t -> now - t > SEEN_TTL_MS);
        if (seen.size() > MAX_SEEN) seen.clear();
        String key = signature + '|' + detail;
        return seen.putIfAbsent(key, now) != null;
    }

    /** {@code runtime_sample} 明细 → 位置 / 鼠标轨迹 / 点击事件（字段缺失即跳过，不猜值）。 */
    private void feedSample(BufferedInputSource source, String detail) {
        if (detail.isEmpty()) return;
        Map<String, Object> m;
        try {
            m = Json.decodeObject(detail);
        } catch (RuntimeException e) {
            return;
        }
        long now = System.currentTimeMillis();
        Object t = m.get("t");
        if (t instanceof Number n && n.longValue() > 0) now = n.longValue();

        Vec3 pos = vec3(m.get("pos"));
        if (pos != null) {
            boolean onGround = Boolean.TRUE.equals(m.get("on_ground"));
            source.pushPosition(new PositionSample(now, pos, new Vec3(0, 0, 0), onGround, false, false));
        }
        Object delta = m.get("mouse_delta");
        if (delta instanceof List<?> d && d.size() >= 2 && d.get(0) instanceof Number dx && d.get(1) instanceof Number dy) {
            cumulativeX += dx.doubleValue();
            cumulativeY += dy.doubleValue();
            source.pushTrajectory(new Point2D(cumulativeX, cumulativeY));
        }
        boolean pressed = Boolean.TRUE.equals(m.get("attack_pressed"));
        if (pressed && !attackPressed) source.pushInput(InputEvent.click(now));
        attackPressed = pressed;
    }

    private static Vec3 vec3(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3) return null;
        if (!(list.get(0) instanceof Number x) || !(list.get(1) instanceof Number y)
                || !(list.get(2) instanceof Number z)) {
            return null;
        }
        return new Vec3(x.doubleValue(), y.doubleValue(), z.doubleValue());
    }

    private static String clamp(String s) {
        if (s == null) return "";
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}