package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.stealth.StealthSnapshot;
import com.potatotv.paccclient.detection.stealth.StealthTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 检测引擎（v5.2）：一次采样按下列顺序推进，任一层命中即返回该层事件。
 *
 * <pre>
 * 1. Java Agent 结论（§5）    → mod 验签 / 类装载 / 字节码完整性 / 异常移动，命中即上报
 * 2. L0 规则 + L1 端侧 AI（§2.3）→ 178 维特征 + 时序/轨迹分析 + 作弊规则融合判定
 * 3. L1 隐身探针（§4）        → PCIe DMA / 注入痕迹 / 调试通道 / 沙箱特征
 * 4. 底层探针与行为兜底        → 既有 LowLevelProbe / BehaviorMonitor
 * </pre>
 *
 * <p>特征与探针数据全部来自真实采样：战斗/移动组由 Java Agent 的 {@code runtime_sample}
 * 推进 {@link BufferedInputSource}（无探针时该组为中性值），环境/JVM/网络组走纯 JDK 内省，
 * 隐身维度走 {@link StealthTelemetry}。**不再有任何随机取样**——旧版的 {@code Math.random()}
 * 演示数据在 v5.2 全部移除。</p>
 *
 * <p>性能开关见 {@link PerfToggles}：采集、端侧 AI、时序分析、隐身探针都可单独关闭（文档 §10.1）。</p>
 */
public final class DetectionEngine {

    private final LowLevelProbe lowLevelProbe = new KernelMemoryProbe();
    private final BehaviorMonitor behaviorMonitor = new BehaviorMonitor();
    private final JavaAgentProbe javaAgentProbe;
    private final StealthDetector stealthDetector = new StealthDetector();
    private final BufferedInputSource inputSource = new BufferedInputSource();
    private final FeatureCollector featureCollector = new FeatureCollector(inputSource);
    private final BruteForceDetector bruteForceDetector;

    /** 最近一次 L0/L1 判定层级（供本地控制服务与联调观测）。 */
    private volatile LayeredDecision.Decision lastDecision = LayeredDecision.Decision.PERIODIC;
    /** 最近一次采样的特征覆盖度（验收 A02 的运行时观测值）。 */
    private volatile int lastCoverage;

    public DetectionEngine() {
        this(null);
    }

    /**
     * @param aiModel 端侧模型（由 {@code ModelRepository} 装载）；{@code null} 表示无模型运行
     */
    public DetectionEngine(LocalAiModel aiModel) {
        this(aiModel, new JavaAgentProbe());
    }

    /** 测试接缝：注入探针（生产固定走 {@code 127.0.0.1:17020} 的默认端点）。 */
    DetectionEngine(LocalAiModel aiModel, JavaAgentProbe javaAgentProbe) {
        LocalAiModel model = aiModel == null ? new LocalAiModel() : aiModel;
        this.javaAgentProbe = javaAgentProbe == null ? new JavaAgentProbe() : javaAgentProbe;
        this.bruteForceDetector = new BruteForceDetector(model, null);
    }

    /** 行为采样落点：Java Agent 轮询把 {@code runtime_sample} 写进来。 */
    public BufferedInputSource inputSource() {
        return inputSource;
    }

    public LayeredDecision.Decision lastDecision() {
        return lastDecision;
    }

    /** 最近一帧有真实数据支撑的维度数。 */
    public int lastCoverage() {
        return lastCoverage;
    }

    /**
     * 执行一次完整采样。
     *
     * @param clientRisk 保留参数（v5.2 起演示路径已移除，实现不再使用；调用方仍按既有约定传入，
     *                   待端云特征上报接入后用作 {@code local_risk_score} 基线）
     * @return 合并后的端侧事件（可能为空表示本周期无异常）
     */
    public Optional<DetectionEvent> sample(int clientRisk) {
        // ---- 1. Java Agent 结论与行为采样 ----
        for (DetectionEvent e : javaAgentProbe.poll(inputSource)) {
            return Optional.of(e);
        }

        // ---- 2. L0/L1：特征 + 规则 + 端侧 AI ----
        FeatureVector behaviorFv = null;
        if (PerfToggles.enabled(PerfToggles.FEATURE_COLLECTION)) {
            behaviorFv = featureCollector.collect();
            lastCoverage = featureCollector.coverage();
            BruteForceDetector.Verdict verdict = bruteForceDetector.evaluate(
                    behaviorFv, clickIntervals(), inputSource.mouseTrajectory(), inputSource.aimTarget());
            LayeredDecision.Decision decision = LayeredDecision.decide(
                    LayeredDecision.triggerReason(verdict.ruleHit(), verdict.aiConfidence()),
                    verdict.ruleHit(), verdict.aiConfidence());
            lastDecision = decision;
            // LOCAL_BLOCK：端侧直接处置；REPORT_CLOUD：交给云端精判；PERIODIC：无信号不打扰云端
            if (decision != LayeredDecision.Decision.PERIODIC && verdict.event().isPresent()) {
                return verdict.event();
            }
        }

        // ---- 3. L1 隐身探针（§4） ----
        if (PerfToggles.enabled(PerfToggles.STEALTH_PROBES)) {
            StealthSnapshot stealth = StealthTelemetry.probe();
            Optional<DetectionEvent> event = stealthDetector.inspect(stealth);
            if (event.isPresent()) return event;
        }

        // ---- 4. 底层探针与行为兜底 ----
        long cps = behaviorFv == null ? 0 : Math.round(behaviorFv.get("feature_click_cps"));
        double aim = behaviorFv == null ? 0 : behaviorFv.get("feature_killaura_angle_speed");
        return lowLevelProbe.scan()
                .or(() -> behaviorMonitor.inspectInput(cps, aim))
                .or(() -> javaAgentProbe.scanForMods(false));
    }

    /** 最近点击间隔（ms，旧→新）：取输入缓冲里的 CLICK 事件时间差。 */
    private List<Long> clickIntervals() {
        List<InputEvent> clicks = inputSource.recentInputs(100);
        List<Long> times = new ArrayList<>();
        for (InputEvent e : clicks) {
            if (e != null && e.kind() == InputEvent.Kind.CLICK) times.add(e.timestampMillis());
        }
        List<Long> intervals = new ArrayList<>(Math.max(0, times.size() - 1));
        for (int i = 1; i < times.size(); i++) {
            long gap = times.get(i) - times.get(i - 1);
            if (gap > 0) intervals.add(gap);
        }
        return intervals;
    }
}