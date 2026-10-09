package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 人类行为模拟度检测（文档 §4.9）：把现有行为分析产出的特征（点击间隔统计、轨迹平滑度、
 * 微抖动熵）综合成一个「脚本化程度」评分。
 *
 * <p>文档伪代码引用的原始序列（点击间隔列表 / 鼠标轨迹 / 反应时间）在 {@code FeatureVector}
 * 中已被 {@code FeatureCollector} 通过 {@code ClickIntervalAnalyzer} / {@code TrajectoryAnalyzer}
 * 归约成统计量，因此这里直接消费特征，避免重复保留原始输入：</p>
 * <ul>
 *   <li>点击过于规律：间隔变异系数 {@code feature_click_interval_cv} 极低且 CPS 偏高；</li>
 *   <li>轨迹过于平滑：{@code feature_aim_smoothness} 接近 1；</li>
 *   <li>操作过于机械：微抖动 Shannon 熵 {@code feature_aim_micro_jitter_entropy} 偏低；</li>
 *   <li>节律周期性：由变异系数反推的规整度 {@code 1 - cv} 偏高。</li>
 * </ul>
 *
 * <p>多信号加权到阈值 40 才判定，单指标不触发（文档 §9 注意事项 1）。
 * 产出扩展特征 {@code ext_behavior_*}，供 {@code behavior_anomaly} PRL 规则读取。</p>
 */
public final class BehaviorAIScanner implements Detector {

    private static final String ID = "behavior_ai_scanner";
    /** 行为窗口 2s 分析一次（文档 §8.3）。 */
    private static final long INTERVAL_MS = 2_000L;
    private static final int THRESHOLD = 40;
    /** 脚本点击间隔变异系数上限（越低越像固定间隔连点）。 */
    private static final double REGULAR_CV = 0.15;
    /** 触发「规律点击」所需的最低每秒点击数。 */
    private static final double MIN_CPS = 8.0;
    /** 轨迹平滑度阈值，超过视为过于机械。 */
    private static final double SMOOTH_THRESHOLD = 0.95;
    /** 微抖动熵下限（nats），低于视为脚本。 */
    private static final double JITTER_ENTROPY = 1.5;
    /** 节律周期性阈值。 */
    private static final double PERIODIC_THRESHOLD = 0.8;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        FeatureVector fv = ctx.features();
        double cv = fv == null ? 0 : fv.get("feature_click_interval_cv");
        double cps = fv == null ? 0 : fv.get("feature_click_cps");
        double smooth = fv == null ? 0 : fv.get("feature_aim_smoothness");
        double jitter = fv == null ? 0 : fv.get("feature_aim_micro_jitter_entropy");
        if (jitter == 0 && fv != null) {
            jitter = fv.get("feature_jitter_entropy");
        }
        // 无点击样本时 cv=0，不能据此反推出「极端规律」，故仅在 cv>0 时计算周期性
        double periodicity = cv > 0 ? clamp01(1.0 - cv) : 0;

        List<String> anomalies = new ArrayList<>();
        int score = 0;
        if (cv > 0 && cv < REGULAR_CV && cps >= MIN_CPS) {
            anomalies.add("regular_click:" + String.format("%.3f", cv));
            score += 25;
        }
        if (smooth > SMOOTH_THRESHOLD) {
            anomalies.add("over_smooth_trajectory:" + String.format("%.3f", smooth));
            score += 20;
        }
        if (jitter > 0 && jitter < JITTER_ENTROPY) {
            anomalies.add("low_jitter_entropy:" + String.format("%.2f", jitter));
            score += 30;
        }
        if (periodicity > PERIODIC_THRESHOLD) {
            anomalies.add("high_periodicity:" + String.format("%.2f", periodicity));
            score += 20;
        }

        int capped = Math.min(100, score);
        ctx.putExtended("ext_behavior_score", capped);
        ctx.putExtended("ext_behavior_click_entropy", jitter);
        ctx.putExtended("ext_behavior_trajectory_smoothness", smooth);
        ctx.putExtended("ext_behavior_periodicity", periodicity);

        if (score < THRESHOLD) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("anomalies", anomalies);
        detail.put("score", capped);
        return Optional.of(new DetectionEvent(
                "behavior_anomaly",
                capped >= 70 ? "high" : "medium",
                capped,
                null, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}