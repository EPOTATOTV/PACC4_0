package com.potatotv.pacc.service.detection.v46;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.ConfidenceService;
import com.potatotv.pacc.util.ml.IsolationForest;
import com.potatotv.pacc.util.ml.LinearAutoencoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v4.6 零日外挂检测：对未知外挂的分布外异常识别。
 *
 * <p>三路信号融合：</p>
 * <ul>
 *   <li>孤立森林独离分数 ({@code iso-score})：无需先验签名的统计离群；</li>
 *   <li>线性自编码重构误差 ({@code recon-error})：偏离正常主成分流形的程度；</li>
 *   <li>个人行为基线偏离 ({@code baseline-deviation})：与该玩家历史基线的 z-score 距离。</li>
 * </ul>
 * 加权融合为综合分 → 复用 {@link ConfidenceService} 分级：MEDIUM+ 记录为发现，
 * LOW 证书进入主动学习队列由运营复核回流。模型在特征空间泛化，可覆盖未知外挂（零日）。
 */
@Service
public class ZeroDayAnomalyService {

    /** 参与零日分析的判别特征子集（同时存在于基岩版与 Java 版特征空间）。 */
    static final String[] FEATURES = {
            "feature_killaura_angle_speed",
            "feature_aim_smoothness",
            "feature_click_interval_cv",
            "feature_semantic_killaura",
            "feature_speed_ratio",
            "feature_human_likeness",
            "feature_trajectory_curvature",
            "feature_jitter_entropy"
    };

    private final ZeroDayFindingRepository findings;
    private final ConfidenceService confidenceService;
    private final double isoWeight;
    private final double reconWeight;
    private final double baselineWeight;
    private final double reconScale;
    private final double baselineScale;

    public ZeroDayAnomalyService(ZeroDayFindingRepository findings,
                                 ConfidenceService confidenceService,
                                 @Value("${pacc.zero-day.iso-weight:0.5}") double isoWeight,
                                 @Value("${pacc.zero-day.recon-weight:0.3}") double reconWeight,
                                 @Value("${pacc.zero-day.baseline-weight:0.2}") double baselineWeight,
                                 @Value("${pacc.zero-day.recon-scale:1.2}") double reconScale,
                                 @Value("${pacc.zero-day.baseline-scale:3.0}") double baselineScale) {
        this.findings = findings;
        this.confidenceService = confidenceService;
        this.isoWeight = isoWeight;
        this.reconWeight = reconWeight;
        this.baselineWeight = baselineWeight;
        this.reconScale = reconScale;
        this.baselineScale = baselineScale;
    }

    /**
     * 对一条特征向量执行零日异常评估并落库。
     *
     * @param pteid       玩家
     * @param edition     BEDROCK / JAVA
     * @param fv          待评估特征向量
     * @param baselineRows 基线样本（该玩家或全局的正常行为），null 时用内置人类基线
     * @return 评估报告
     */
    public Map<String, Object> assess(String pteid, String edition, FeatureVector fv, double[][] baselineRows) {
        double[][] base = (baselineRows != null && baselineRows.length > 0)
                ? baselineRows : defaultHumanBaseline();
        double[] x = toArray(fv);

        IsolationForest forest = new IsolationForest(base, 40, 16, 8, 42L);
        double iso = forest.anomalyScore(x);

        LinearAutoencoder ae = LinearAutoencoder.fit(base, 3, 40, 7L);
        double recon = ae.reconstructionError(x);

        double baseDev = baselineDeviation(base, x);

        double reconRatio = 1 - Math.exp(-recon / reconScale);
        double baseRatio = 1 - Math.exp(-baseDev / baselineScale);
        int composite = (int) Math.round(100
                * (isoWeight * iso + reconWeight * reconRatio + baselineWeight * baseRatio));
        composite = Math.max(0, Math.min(100, composite));
        String tier = confidenceService.classify(composite).name();

        ZeroDayFinding finding = findings.save(ZeroDayFinding.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .pteid(pteid)
                .edition(edition)
                .isoScore(iso)
                .reconError(recon)
                .baselineDeviation(baseDev)
                .compositeScore(composite)
                .confidenceTier(tier)
                .featuresJson(basicJson(fv))
                .build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finding_id", finding.getId());
        out.put("pteid", pteid);
        out.put("edition", edition);
        out.put("iso_score", iso);
        out.put("recon_error", recon);
        out.put("baseline_deviation", baseDev);
        out.put("composite", composite);
        out.put("confidence_tier", tier);
        return out;
    }

    /** 便捷重载：使用内置人类基线。 */
    public Map<String, Object> assess(String pteid, String edition, FeatureVector fv) {
        return assess(pteid, edition, fv, null);
    }

    private double[] toArray(FeatureVector fv) {
        double[] x = new double[FEATURES.length];
        for (int i = 0; i < FEATURES.length; i++) x[i] = fv.get(FEATURES[i]);
        return x;
    }

    /** 归一化 z-score 偏离范数 sqrt(Σ((x_i-μ_i)/σ_i)^2)，σ 趋于 0 时回落。 */
    private double baselineDeviation(double[][] baseline, double[] x) {
        int d = x.length;
        double[] mean = new double[d], sd = new double[d];
        for (double[] row : baseline) for (int j = 0; j < d; j++) mean[j] += row[j];
        for (int j = 0; j < d; j++) mean[j] /= baseline.length;
        for (double[] row : baseline)
            for (int j = 0; j < d; j++) sd[j] += Math.pow(row[j] - mean[j], 2);
        for (int j = 0; j < d; j++) sd[j] = Math.sqrt(sd[j] / baseline.length);
        double norm = 0;
        for (int j = 0; j < d; j++) {
            // 忽略零方差特征（退化），基线尺度极小回落到 x 自身偏离
            double dev = sd[j] < 1e-9 ? Math.abs(x[j] - mean[j]) : (x[j] - mean[j]) / sd[j];
            norm += dev * dev;
        }
        return Math.sqrt(norm);
    }

    private static double[][] defaultHumanBaseline() {
        double base = 1;
        double[][] rows = new double[24][FEATURES.length];
        for (int r = 0; r < rows.length; r++) {
            double jitter = 1 + 0.15 * Math.sin(base * (r + 1));
            rows[r] = new double[]{
                    12.0 * jitter,          // killaura_angle_speed（人类低）
                    0.38 * (1 + 0.1 * Math.cos(r)), // aim_smoothness（人类高，平滑）
                    (0.26 + 0.06 * Math.sin(r * 0.7)), // click_interval_cv（人类抖动 ~0.3）
                    0.05 * (1 + 0.3 * Math.cos(r * 1.3)), // semantic_killaura（低）
                    1.0 + 0.02 * Math.sin(r),              // speed_ratio（≈1）
                    0.85 * (1 + 0.08 * Math.cos(r * 0.5)), // human_likeness（高）
                    0.55,                                  // trajectory_curvature
                    3.2 + 0.4 * Math.sin(r * 0.9)          // jitter_entropy（人类高）
            };
        }
        return rows;
    }

    private static String basicJson(FeatureVector fv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < FEATURES.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(FEATURES[i]).append("\":").append(fv.get(FEATURES[i]));
        }
        return sb.append('}').toString();
    }

    public ZeroDayFindingRepository repository() {
        return findings;
    }
}