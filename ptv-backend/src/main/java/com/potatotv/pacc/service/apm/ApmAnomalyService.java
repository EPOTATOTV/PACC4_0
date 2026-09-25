package com.potatotv.pacc.service.apm;

import com.potatotv.pacc.domain.ApmMetricHourly;
import com.potatotv.pacc.repository.ApmMetricHourlyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * v5.4 §2.5 APM 异常检测（基线偏离）。
 *
 * <p>口径：对某个「指标 +（可选）平台」取最近 7 天的小时聚合行，用 {@code avgValue} 序列算均值与
 * 总体标准差，阈值 = {@code 均值 + sigma × 标准差}，超出阈值的桶判为异常。</p>
 *
 * <p>只用均值/标准差、不引模型，是因为在这里「可解释」比「准」重要：运维看到一条异常告警，
 * 必须能立刻回答「基线是多少、偏了几个 sigma」。样本不足 {@value #MIN_POINTS} 个桶时直接不判——
 * 少数几个点算出来的标准差不具备统计意义，硬判只会制造噪声。标准差为 0（数据完全平直）同样不判，
 * 否则阈值退化成均值，会把一半的桶都标成异常。</p>
 */
@Service
@RequiredArgsConstructor
public class ApmAnomalyService {

    /** 参与判定所需的最少小时桶数。 */
    public static final int MIN_POINTS = 24;
    /** 默认观察窗（7 天）。 */
    public static final int DEFAULT_HOURS = 168;
    /** 观察窗上限。 */
    private static final int MAX_HOURS = 720;

    private final ApmMetricHourlyRepository hourly;

    /** 异常判定灵敏度（默认 3 sigma）。 */
    @Value("${pacc.apm.anomaly-sigma:3.0}")
    private double sigma = 3.0;

    /** 一个异常桶。 */
    public record Anomaly(Instant hour, double value, double threshold,
                          double mean, double stddev, double sigma) { }

    /** 默认 7 天窗的异常检测。 */
    public List<Anomaly> detect(String platform, String metricName) {
        return detect(platform, metricName, DEFAULT_HOURS);
    }

    /**
     * 异常检测。
     *
     * @param platform   平台标识，空表示不限
     * @param metricName 指标名
     * @param hours      观察窗小时数（内部夹到 [1, 720]）
     * @return 超阈值的桶，按时间升序；样本不足或无波动时返回空列表
     */
    public List<Anomaly> detect(String platform, String metricName, int hours) {
        if (metricName == null || metricName.isBlank()) return List.of();
        int h = Math.max(1, Math.min(MAX_HOURS, hours));
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(h, ChronoUnit.HOURS);

        List<ApmMetricHourly> rows = hourly.findHistoryByMetric(metricName, platform == null ? "" : platform, from);
        if (rows.size() < MIN_POINTS) return List.of();

        double mean = 0;
        for (ApmMetricHourly row : rows) mean += row.getAvgValue();
        mean /= rows.size();
        double variance = 0;
        for (ApmMetricHourly row : rows) variance += (row.getAvgValue() - mean) * (row.getAvgValue() - mean);
        double stddev = Math.sqrt(variance / rows.size());
        if (stddev <= 0) return List.of();

        double threshold = mean + sigma * stddev;
        List<Anomaly> out = new ArrayList<>();
        for (ApmMetricHourly row : rows) {
            if (row.getAvgValue() > threshold) {
                out.add(new Anomaly(row.getMetricHour(), round4(row.getAvgValue()),
                        round4(threshold), round4(mean), round4(stddev), sigma));
            }
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}