package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.input.InputTimingScanner;
import com.potatotv.paccclient.detection.network.NetworkBehaviorScanner;
import com.potatotv.paccclient.detection.vision.ScreenVisionScanner;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 专项检测器调度器（文档 §8.3：各检测器独立周期 + 预算）。
 *
 * <p>持有全部 {@link Detector}，按各自 {@link Detector#intervalMs()} 到点执行。三条约定：</p>
 * <ul>
 *   <li><b>周期独立</b>：进程 5s / 模块 10s / 驱动 30s …… 各跑各的，不是每个采样周期全跑；</li>
 *   <li><b>异常隔离</b>：单个检测器抛异常只记它自己，不影响同周期其它检测器；</li>
 *   <li><b>熔断恢复</b>：同一检测器连续失败 {@value #MAX_FAILURES} 次后，下一个到点周期先调
 *       {@link Detector#onRecover()} 复位再试一次，避免坏检测器拖垮整个链路。</li>
 * </ul>
 *
 * <p>{@link #tick(DetectContext)} 返回本周期命中的最高严重度事件（可能为空），保持与
 * {@code DetectionEngine}「每周期最多一个事件」的契约一致；所有检测器的扩展特征都已写回
 * {@link DetectContext#features()}，供 PRL 规则读取做统一加权判定。</p>
 */
public final class ScannerRunner {

    /** 连续失败达到该次数进入熔断，下个周期先复位再试。 */
    private static final int MAX_FAILURES = 5;

    private final List<Detector> detectors;
    private final Map<Detector, Long> nextRun = new IdentityHashMap<>();
    private final Map<Detector, Integer> failures = new IdentityHashMap<>();

    public ScannerRunner(List<Detector> detectors) {
        this.detectors = new CopyOnWriteArrayList<>(detectors == null ? List.of() : detectors);
    }

    /** 核心内置检测器集合。后续批次新增的检测器在此登记。 */
    public static ScannerRunner withBuiltinDetectors() {
        return new ScannerRunner(List.of(
                new ProcessScanner(),
                new ModuleScanner(),
                new DriverScanner(),
                new FileScanner(),
                new RegistryScanner(),
                new InputDeviceScanner(),
                new NetworkScanner(),
                new MemoryScanner(),
                new BehaviorAIScanner(),
                new SignatureMatcher(),
                // ---- 三层检测架构批次（文档 §6.1 第一/二批）：各检测器内部按层开关自门控，
                // 关闭的层（网络代理 / 屏幕）在 detect() 首行直接返回空，不产生任何采集开销 ----
                new NetworkBehaviorScanner(),
                new ScreenVisionScanner(),
                new InputTimingScanner(),
                new DllSignatureScanner(),
                new InjectionScanner(),
                new UnsignedExecutableScanner(),
                new KernelCallbackScanner()));
    }

    /** 内置检测器 + 插件注册的检测器（文档 §2.4 步骤 7）。 */
    public static ScannerRunner withBuiltinDetectors(Collection<Detector> pluginDetectors) {
        ScannerRunner runner = withBuiltinDetectors();
        runner.addDetectors(pluginDetectors);
        return runner;
    }

    /**
     * 追加检测器（插件加载完成后接入）。应在开始 {@link #tick} 之前调用；
     * 允许运行期追加，但新增检测器的首个周期从下一次 tick 起算。
     */
    public void addDetectors(Collection<Detector> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        detectors.addAll(extra);
    }

    /** 已登记的检测器（只读，供诊断 / 测试）。 */
    public List<Detector> detectors() {
        return List.copyOf(detectors);
    }

    /**
     * 推进一轮调度：执行所有到点的检测器，返回命中里最高严重度的一条。
     *
     * @param ctx 执行上下文（探针 + 共享特征向量）
     * @return 本周期命中的最高严重度事件；无命中为空
     */
    public Optional<DetectionEvent> tick(DetectContext ctx) {
        if (ctx == null || detectors.isEmpty()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        List<DetectionEvent> hits = new ArrayList<>();
        for (Detector detector : detectors) {
            long interval = detector.intervalMs();
            if (interval <= 0) {
                // 事件驱动型不参与定时调度
                continue;
            }
            Long due = nextRun.get(detector);
            if (due != null && now < due) {
                continue;
            }
            nextRun.put(detector, now + interval);
            runOne(detector, ctx, hits);
        }
        return mostSevere(hits);
    }

    private void runOne(Detector detector, DetectContext ctx, List<DetectionEvent> hits) {
        try {
            if (failures.getOrDefault(detector, 0) >= MAX_FAILURES) {
                detector.onRecover();
                failures.put(detector, 0);
            }
            detector.detect(ctx).ifPresent(hits::add);
        } catch (RuntimeException e) {
            failures.merge(detector, 1, Integer::sum);
        }
    }

    private static Optional<DetectionEvent> mostSevere(List<DetectionEvent> hits) {
        DetectionEvent top = null;
        for (DetectionEvent e : hits) {
            if (top == null || rank(e.severity()) > rank(top.severity())) {
                top = e;
            }
        }
        return Optional.ofNullable(top);
    }

    private static int rank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }
}