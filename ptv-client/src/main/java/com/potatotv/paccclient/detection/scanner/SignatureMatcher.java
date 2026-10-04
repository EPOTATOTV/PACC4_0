package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.GlobMatcher;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.PathHit;
import com.potatotv.paccclient.probe.PathPattern;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.RegistryPattern;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 作弊软件特征库匹配引擎（文档 §3.2 / §3.3）。
 *
 * <p>把 80+ 种已知作弊软件抽象成「多指标加权签名」：每个签名由若干 {@link Indicator} 组成，
 * 命中指标按权重累加，达到签名阈值即判定。基线库随客户端发布（{@code /signatures/pacc-signatures.json}），
 * 运行时可通过现有 {@code SignatureSync} 通道增量更新（文档 §9 注意事项 5）。</p>
 *
 * <p>每次扫描一次性采集进程 / 模块 / 驱动 / 文件 / 注册表快照，避免逐指标重复系统调用
 * （文档 §3.3）；进程名 / 窗口标题 / 模块 / 驱动走 {@link GlobMatcher}，文件路径 / 注册表键名
 * 先由探针按全部相关模式批量取回，再逐指标回匹配。命中产出 {@code signature_hit} 事件进入
 * 现有事件总线，与各专项检测器共用同一处置链路（文档 §9 注意事项 4）。</p>
 */
public final class SignatureMatcher implements Detector {

    private static final String ID = "signature_matcher";
    /** 特征库全量扫描 30s 一次（文档 §8.3 归入进程/模块类预算）。 */
    private static final long INTERVAL_MS = 30_000L;
    private static final String TARGET_PROCESS = "Minecraft.Windows.exe";
    private static final String RESOURCE = "/signatures/pacc-signatures.json";

    private final List<Signature> signatures;

    /** 默认构造：装载内置基线特征库。 */
    public SignatureMatcher() {
        this(loadBuiltin());
    }

    public SignatureMatcher(List<Signature> signatures) {
        this.signatures = signatures == null ? List.of() : List.copyOf(signatures);
    }

    /** 单个匹配指标。 */
    public record Indicator(String type, String pattern, int weight) {
    }

    /** 一条作弊软件签名。 */
    public record Signature(String id, String name, String severity, int threshold, List<Indicator> indicators) {
    }

    /** 一次签名命中。 */
    public record SignatureHit(String id, String name, String severity, int score, List<String> matched) {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    /** 已装载的签名（供诊断 / 测试）。 */
    public List<Signature> signatures() {
        return signatures;
    }

    /**
     * 执行一次全量扫描，返回达到各自阈值的签名命中。
     *
     * @param probe 系统探针
     */
    public List<SignatureHit> scan(SystemProbe probe) {
        ProcessSnapshot processes = probe.isSupported(SystemProbe.Capability.PROCESSES)
                ? probe.snapshotProcesses() : ProcessSnapshot.empty();
        ModuleSnapshot modules = probe.isSupported(SystemProbe.Capability.MODULES)
                ? probe.snapshotModules(TARGET_PROCESS) : ModuleSnapshot.empty(TARGET_PROCESS);
        DriverSnapshot drivers = probe.isSupported(SystemProbe.Capability.DRIVERS)
                ? probe.snapshotDrivers() : DriverSnapshot.empty();
        List<PathHit> files = probe.isSupported(SystemProbe.Capability.FILES)
                ? probe.scanPaths(filePatterns(), FileScanner.scanRoots()) : List.of();
        List<RegistryHit> registry = probe.isSupported(SystemProbe.Capability.REGISTRY)
                ? probe.scanRegistry(registryPatterns()) : List.of();

        List<SignatureHit> hits = new ArrayList<>();
        for (Signature sig : signatures) {
            int score = 0;
            List<String> matched = new ArrayList<>();
            for (Indicator ind : sig.indicators()) {
                if (matches(ind, processes, modules, drivers, files, registry)) {
                    score += ind.weight();
                    matched.add(ind.type() + ":" + ind.pattern());
                }
            }
            if (score >= sig.threshold()) {
                hits.add(new SignatureHit(sig.id(), sig.name(), sig.severity(), score, matched));
            }
        }
        return hits;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        List<SignatureHit> hits = scan(ctx.systemProbe());
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        SignatureHit top = hits.get(0);
        for (SignatureHit hit : hits) {
            if (hit.score() > top.score()) {
                top = hit;
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("signatures", hits.stream().map(SignatureHit::id).toList());
        detail.put("score", top.score());
        return Optional.of(new DetectionEvent(
                "signature_hit",
                top.severity(),
                Math.min(100, top.score()),
                top.name(), null, top.id(), ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    // ------------------------------------------------------------------ 匹配

    private static boolean matches(Indicator ind, ProcessSnapshot processes, ModuleSnapshot modules,
                                   DriverSnapshot drivers, List<PathHit> files, List<RegistryHit> registry) {
        return switch (ind.type()) {
            case "process_name" -> processes.processes().stream()
                    .anyMatch(p -> GlobMatcher.matches(ind.pattern(), p.name()));
            case "window_title" -> processes.processes().stream()
                    .anyMatch(p -> GlobMatcher.matches(ind.pattern(), p.windowTitle()));
            case "module" -> modules.modules().stream()
                    .anyMatch(m -> GlobMatcher.matches(ind.pattern(), m.name()));
            case "driver" -> drivers.drivers().stream()
                    .anyMatch(d -> GlobMatcher.matches(ind.pattern(), d.name()));
            case "file_path" -> files.stream()
                    .anyMatch(f -> GlobMatcher.matches(ind.pattern(), f.path()));
            case "registry" -> registry.stream()
                    .anyMatch(r -> GlobMatcher.matches(ind.pattern(), r.key()));
            default -> false;
        };
    }

    private List<PathPattern> filePatterns() {
        List<PathPattern> out = new ArrayList<>();
        for (Signature sig : signatures) {
            for (Indicator ind : sig.indicators()) {
                if ("file_path".equals(ind.type())) {
                    out.add(new PathPattern(ind.pattern(), ind.weight()));
                }
            }
        }
        return out;
    }

    private List<RegistryPattern> registryPatterns() {
        List<RegistryPattern> out = new ArrayList<>();
        for (Signature sig : signatures) {
            for (Indicator ind : sig.indicators()) {
                if ("registry".equals(ind.type())) {
                    out.add(new RegistryPattern(ind.pattern(), ind.weight()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 特征库装载

    private static List<Signature> loadBuiltin() {
        try (InputStream in = SignatureMatcher.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            String text = new String(in.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
            Object decoded = Json.decode(text);
            if (!(decoded instanceof Map<?, ?> root) || !(root.get("signatures") instanceof List<?> list)) {
                return List.of();
            }
            List<Signature> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add(parseSignature(m));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static Signature parseSignature(Map<?, ?> m) {
        List<Indicator> indicators = new ArrayList<>();
        if (m.get("indicators") instanceof List<?> inds) {
            for (Object item : inds) {
                if (item instanceof Map<?, ?> im) {
                    indicators.add(new Indicator(
                            str(im.get("type")), str(im.get("pattern")), (int) num(im.get("weight"))));
                }
            }
        }
        return new Signature(str(m.get("id")), str(m.get("name")), str(m.get("severity")),
                (int) num(m.get("threshold")), indicators);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}