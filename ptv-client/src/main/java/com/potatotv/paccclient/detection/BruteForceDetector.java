package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.ai.InferenceResult;
import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer;
import com.potatotv.paccclient.detection.analysis.TemporalAnomalyDetector;
import com.potatotv.paccclient.detection.analysis.TrajectoryAnalyzer;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRuleRegistry;
import com.potatotv.paccclient.detection.samples.Point2D;

import java.util.List;
import java.util.Optional;

/**
 * 暴力外挂检测器（文档 §3.1）：三层时序/分布模型 + L0 规则层 + L1 端侧 AI 的融合判定。
 *
 * <p>v5.2 起，判定不再只依赖 {@code cps > 14} 这类硬阈值（legit 高 CPS 玩家误报高），
 * 而是把下列多路信号加权融合：</p>
 * <ol>
 *   <li><b>硬阈值（L0 规则层）</b>：保留原有 5 条明确违规阈值，作为高置信直判；</li>
 *   <li><b>点击间隔分布</b>（{@link ClickIntervalAnalyzer}）：与拟合对数正态做 KS 检验；</li>
 *   <li><b>鼠标轨迹</b>（{@link TrajectoryAnalyzer}）：三次贝塞尔拟合误差 + 目标吸引 + 瞬移占比；</li>
 *   <li><b>时序自编码</b>（{@link TemporalAnomalyDetector}）：序列重构误差；</li>
 *   <li><b>端侧 AI</b>（{@link LocalAiModel}）：模型推理分，回退（无模型/超时）时按 0 处理，
 *       不参与判定；</li>
 *   <li><b>作弊类型规则</b>（{@link CheatRuleRegistry}）：文档 §3.2 的 15 种类型。</li>
 * </ol>
 *
 * <p>性能开关见 {@link PerfToggles}：{@code brute_force} 关闭时退化为纯硬阈值，
 * {@code local_ai} 关闭时不做模型推理（文档 §10.1）。</p>
 */
public final class BruteForceDetector {

    // ---- L0 硬阈值（保留原判定口径，作为高置信直判） ----
    private static final double CPS_HARD = 14.0;
    private static final double ANGLE_HARD = 45.0;
    private static final double SPEED_HARD = 1.5;
    private static final double FLY_HARD = 1.2;
    private static final double REACH_HARD = 4.0;

    // ---- 各层信号的分值权重 ----
    private static final int W_CPS_HARD = 55;
    private static final int W_ANGLE_HARD = 55;
    private static final int W_SPEED_HARD = 50;
    private static final int W_FLY_HARD = 65;
    private static final int W_REACH_HARD = 50;
    private static final int W_CLICK_HIGHLY_LIKELY = 45;
    private static final int W_CLICK_SUSPECTED = 20;
    private static final int W_ATTRACTION = 40;
    private static final int W_SNAP = 20;
    private static final int W_TEMPORAL = 25;
    private static final int W_TEMPORAL_STRONG = 15;
    private static final int W_AI_MAX = 50;

    /** 目标吸引判定阈值（余弦相似度）。 */
    private static final double ATTRACTION_THRESHOLD = 0.8;
    /** 目标吸引同时要求拟合误差偏高，避免把人类直甩判成自瞄。 */
    private static final double ATTRACTION_MIN_RMSE = 5.0;
    /** 瞬移占比阈值。 */
    private static final double SNAP_RATIO_THRESHOLD = 0.5;
    /** 时序重构误差的“显著异常”阈值（MSE，量纲随特征规模缩放，取保守值）。 */
    private static final double TEMPORAL_SIGNIFICANT = 0.5;
    /** 时序重构误差的“强烈异常”阈值。 */
    private static final double TEMPORAL_STRONG = 1.0;

    private final ClickIntervalAnalyzer clickAnalyzer = new ClickIntervalAnalyzer();
    private final TrajectoryAnalyzer trajectoryAnalyzer = new TrajectoryAnalyzer();
    private final CheatRuleRegistry registry = new CheatRuleRegistry();
    private final LocalAiModel aiModel;
    private final TemporalAnomalyDetector temporalDetector;

    /** 默认构造：无模型（AI 回退、时序恒 0），仅走规则 + 分析器。 */
    public BruteForceDetector() {
        this(null, null);
    }

    /**
     * 注入端侧模型与时序模型。
     *
     * @param aiModel          端侧 AI 模型，{@code null} 表示未加载
     * @param temporalDetector 时序异常检测器，{@code null} 表示未加载
     */
    public BruteForceDetector(LocalAiModel aiModel, TemporalAnomalyDetector temporalDetector) {
        this.aiModel = aiModel == null ? new LocalAiModel() : aiModel;
        this.temporalDetector = temporalDetector == null ? new TemporalAnomalyDetector() : temporalDetector;
    }

    /**
     * 一次评估的完整结论。
     *
     * @param event        端侧事件（未达上报条件为空）
     * @param riskScore    综合风险分（0-100）
     * @param aiConfidence 端侧 AI 置信度（0-1；回退为 0）
     * @param ruleHit      L0 规则层是否命中（含分析器与作弊类型规则）
     * @param triggerType  触发类型（作弊类型 code，或行为类型）
     * @param context      本次使用的分析上下文（可直接交给云端上报链路）
     */
    public record Verdict(Optional<DetectionEvent> event, int riskScore, double aiConfidence,
                          boolean ruleHit, String triggerType, AnalysisContext context) {
    }

    /**
     * 采样一次行为特征并生成特征向量（战斗 + 移动维度的代表键，向后兼容旧调用）。
     *
     * @param clicksPerSecond   点击频率（Hz）
     * @param verticalAimDelta  瞄准垂直位移（弧度/秒）
     * @param horizontalSpeed   水平移动速度（方块/秒）
     * @param verticalSpeed     垂直移动速度（方块/秒）
     * @param attackDistance    攻击距离（方块）
     */
    public FeatureVector sample(double clicksPerSecond, double verticalAimDelta,
                                double horizontalSpeed, double verticalSpeed, double attackDistance) {
        FeatureVector fv = new FeatureVector();
        // ---- 战斗特征（前 52 维中的代表维度） ----
        fv.put("feature_click_cps", clamp(clicksPerSecond, 0, 30));
        fv.put("feature_click_interval_var", 0.1);      // 端侧估算
        fv.put("feature_click_interval_cv", 0.1);
        fv.put("feature_killaura_angle_speed", Math.abs(verticalAimDelta) * 60.0);
        fv.put("feature_killaura_mean", Math.abs(verticalAimDelta) * 50.0);
        fv.put("feature_aim_smoothness", 0.5 - Math.min(0.45, Math.abs(verticalAimDelta)));
        fv.put("feature_semantic_killaura", 0.0);       // 语义层由 PTV 填充
        fv.put("feature_reach_distance", clamp(attackDistance, 0, 8));
        fv.put("feature_criticals_rate", 0.0);
        // ---- 移动特征（44 维中的代表维度） ----
        fv.put("feature_speed_ratio", clamp(horizontalSpeed / 5.6, 0, 3)); // 基岩/Java 平均 5.6 方块/秒
        fv.put("feature_fly_vertical_speed", clamp(verticalSpeed, 0, 5));
        fv.put("feature_velocity_ratio", 1.0);
        fv.put("feature_nofall_violations", 0);
        fv.put("feature_scaffold_block_per_sec", 0);
        fv.put("feature_fastplace_block_per_sec", 0);
        fv.put("feature_fastbreak_block_per_sec", 0);
        fv.put("feature_nuker_break_radius", 0);
        return fv;
    }

    /**
     * 端侧预判定（仅特征向量，无时序上下文；等价于空点击/轨迹输入）。
     *
     * @param fv 行为特征向量
     */
    public Optional<DetectionEvent> inspect(FeatureVector fv) {
        return inspect(fv, List.of(), List.of(), null);
    }

    /**
     * 端侧预判定（完整上下文）。
     *
     * @param fv             行为特征向量
     * @param clickIntervals 最近点击间隔（ms）
     * @param trajectory     最近鼠标轨迹点
     * @param target         当前瞄准目标，可为 {@code null}
     * @return 命中时返回事件（type 取作弊类型，severity 随风险分），否则为空
     */
    public Optional<DetectionEvent> inspect(FeatureVector fv, List<Long> clickIntervals,
                                            List<Point2D> trajectory, Point2D target) {
        return evaluate(fv, clickIntervals, trajectory, target).event();
    }

    /**
     * 融合判定，返回完整结论（供 {@link LayeredDecision} 决定本地处置 / 上报云端）。
     * 与 {@link #inspect(FeatureVector, List, List, Point2D)} 使用同一次计算，不重复推理。
     */
    public Verdict evaluate(FeatureVector fv, List<Long> clickIntervals,
                            List<Point2D> trajectory, Point2D target) {
        boolean analyzersOn = PerfToggles.enabled(PerfToggles.BRUTE_FORCE);
        boolean aiOn = PerfToggles.enabled(PerfToggles.LOCAL_AI);

        ClickIntervalAnalyzer.ClickAnalysis click = analyzersOn
                ? clickAnalyzer.analyze(clickIntervals)
                : ClickIntervalAnalyzer.ClickAnalysis.insufficient();
        TrajectoryAnalyzer.TrajectoryAnalysis traj = analyzersOn
                ? trajectoryAnalyzer.analyze(trajectory, target)
                : TrajectoryAnalyzer.TrajectoryAnalysis.empty();
        double temporalAnomaly = analyzersOn ? temporalDetector.anomalyScore(fv) : 0.0;

        double aiConfidence = 0.0;
        if (aiOn) {
            InferenceResult ir = aiModel.infer(fv);
            // 回退分是「非零维度占比」的启发式，不是置信度（文档 §2.1.4），必须忽略
            if (!InferenceResult.SOURCE_FALLBACK.equals(ir.source())) {
                aiConfidence = clamp01(ir.score());
            }
        }

        AnalysisContext ctx = new AnalysisContext(click, traj, temporalAnomaly, aiConfidence,
                clickIntervals == null ? List.of() : List.copyOf(clickIntervals));

        int risk = 0;
        String type = null;

        // ---- 1. L0 硬阈值 ----
        double cps = fv.get("feature_click_cps");
        double angle = fv.get("feature_killaura_angle_speed");
        double speed = fv.get("feature_speed_ratio");
        double fly = fv.get("feature_fly_vertical_speed");
        double reach = fv.get("feature_reach_distance");
        boolean cpsHit = cps > CPS_HARD;
        boolean angleHit = angle > ANGLE_HARD;
        boolean speedHit = speed > SPEED_HARD;
        boolean flyHit = fly > FLY_HARD;
        boolean reachHit = reach > REACH_HARD;
        if (cpsHit) risk += W_CPS_HARD;
        if (angleHit) risk += W_ANGLE_HARD;
        if (speedHit) risk += W_SPEED_HARD;
        if (flyHit) risk += W_FLY_HARD;
        if (reachHit) risk += W_REACH_HARD;
        type = firstOf(cpsHit ? "autoclicker" : null, angleHit ? "killaura" : null,
                flyHit ? "fly" : null, speedHit ? "speed" : null, reachHit ? "reach" : null);

        // ---- 2. 点击间隔分布 ----
        boolean clickSuspicious = false;
        if (click.verdict() == ClickIntervalAnalyzer.Verdict.HIGHLY_LIKELY) {
            risk += W_CLICK_HIGHLY_LIKELY;
            clickSuspicious = true;
            if (type == null) type = "autoclicker";
        } else if (click.verdict() == ClickIntervalAnalyzer.Verdict.SUSPECTED) {
            risk += W_CLICK_SUSPECTED;
            clickSuspicious = true;
            if (type == null) type = "autoclicker";
        }

        // ---- 3. 鼠标轨迹 ----
        boolean trajectorySuspicious = false;
        if (trajectory != null && trajectory.size() >= 2) {
            if (traj.attraction() > ATTRACTION_THRESHOLD && traj.rmse() > ATTRACTION_MIN_RMSE) {
                risk += W_ATTRACTION;
                trajectorySuspicious = true;
                if (type == null) type = "killaura";
            }
            if (traj.snapRatio() > SNAP_RATIO_THRESHOLD) {
                risk += W_SNAP;
                trajectorySuspicious = true;
            }
        }

        // ---- 4. 时序自编码 ----
        boolean temporalHit = false;
        if (temporalAnomaly > TEMPORAL_SIGNIFICANT) {
            risk += W_TEMPORAL;
            temporalHit = true;
        }
        if (temporalAnomaly > TEMPORAL_STRONG) risk += W_TEMPORAL_STRONG;

        // ---- 5. 作弊类型规则（文档 §3.2） ----
        CheatFinding top = null;
        if (analyzersOn) {
            List<CheatFinding> hits = registry.evaluate(fv, ctx);
            if (!hits.isEmpty()) {
                top = hits.get(0);
                risk += top.score() / 2;
                if (top.score() >= CheatFinding.REPORT_THRESHOLD) type = top.type().code();
            }
        }

        // ---- 6. 端侧 AI ----
        if (aiConfidence > 0) risk += (int) Math.round(aiConfidence * W_AI_MAX);

        risk = Math.min(100, risk);
        boolean ruleHit = cpsHit || angleHit || speedHit || flyHit || reachHit
                || clickSuspicious || trajectorySuspicious || temporalHit || top != null;
        if (risk <= 0) {
            return new Verdict(Optional.empty(), 0, aiConfidence, false, "behavior", ctx);
        }
        String eventType = type == null ? "behavior" : type;
        String severity = risk >= 70 ? "high" : risk >= 45 ? "medium" : "low";
        DetectionEvent event = new DetectionEvent(eventType, severity, risk,
                "javaw.exe", null, null, "win10_x64", fv.toDetailJson());
        return new Verdict(Optional.of(event), risk, aiConfidence, ruleHit, eventType, ctx);
    }

    private static String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null) return c;
        }
        return null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
