package com.potatotv.pacc.service.apm;

import com.potatotv.pacc.domain.ApmMetric;
import com.potatotv.pacc.domain.ApmMetricHourly;
import com.potatotv.pacc.repository.ApmMetricHourlyRepository;
import com.potatotv.pacc.repository.ApmMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.4 §2.3 APM 小时聚合与原始数据清理。
 *
 * <p>每小时第 5 分钟处理「刚关闭的那一小时」：把原始采样按 {@code 平台|版本|指标} 分组，
 * 排序后算精确分位数（线性插值），再按唯一键 upsert 进 {@code t_apm_metric_hourly}。
 * 任务可重跑：重跑只会覆盖同一唯一键的行，不会产生重复。</p>
 *
 * <p>为什么不用 SQL 近似分位数：不同数据库的百分位函数语义不一致（H2 与 MySQL 都可近似），
 * 而一小时一组的样本量在可控范围内，拉回内存排序算精确值最省事也最一致。
 * 代价是要给单组设上限（{@value #MAX_VALUES_PER_GROUP} 条，取最近的），超限会打日志——
 * 一旦出现这条日志，说明某个指标的单小时采样量已经异常，需要先看客户端上报频率。</p>
 *
 * <p>原始行只保留 {@code pacc.apm.raw-retention-days} 天（默认 7）：原始采样是「个案回查」用的，
 * 趋势看小时表即可，留着只会把库撑大。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApmAggregationService {

    /** 单组参与分位数计算的样本上限（超出只统计最近的部分）。 */
    public static final int MAX_VALUES_PER_GROUP = 20000;

    private final ApmMetricRepository metrics;
    private final ApmMetricHourlyRepository hourly;

    /** 原始采样保留天数（文档 §2.4，默认 7 天）。 */
    @Value("${pacc.apm.raw-retention-days:7}")
    private int rawRetentionDays = 7;

    /** 每小时第 5 分钟聚合上一个整点。 */
    @Scheduled(cron = "0 5 * * * ?")
    public void aggregateHourly() {
        Instant closed = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        try {
            int groups = aggregateHour(closed);
            if (groups > 0) log.info("APM 小时聚合完成 hour={} 分组={}", closed, groups);
        } catch (Exception e) {
            // 单轮聚合失败不能打断调度，下一小时还会再来
            log.warn("APM 小时聚合失败 hour={} err={}", closed, e.getMessage());
        }
    }

    /** 每日 40 分清理过期原始采样。 */
    @Scheduled(cron = "0 40 * * * ?")
    @Transactional
    public void purgeRaw() {
        try {
            Instant cutoff = Instant.now().minus(Math.max(1, rawRetentionDays), ChronoUnit.DAYS);
            int deleted = metrics.deleteByMetricTimeBefore(cutoff);
            if (deleted > 0) log.info("APM 原始采样清理完成 cutoff={} deleted={}", cutoff, deleted);
        } catch (Exception e) {
            log.warn("APM 原始采样清理失败 err={}", e.getMessage());
        }
    }

    /**
     * 聚合指定整点的小时行（公开以便手动补跑与测试）。
     *
     * @param hour 目标小时（内部会截断到整点）
     * @return 写入/更新的分组数
     */
    @Transactional
    public int aggregateHour(Instant hour) {
        Instant from = hour.truncatedTo(ChronoUnit.HOURS);
        Instant to = from.plus(1, ChronoUnit.HOURS);
        List<ApmMetric> rows = metrics.findByMetricTimeBetweenOrderByMetricTimeAsc(from, to);
        if (rows.isEmpty()) return 0;

        Map<String, List<ApmMetric>> groups = new LinkedHashMap<>();
        for (ApmMetric row : rows) {
            groups.computeIfAbsent(groupKey(row), k -> new ArrayList<>()).add(row);
        }

        Instant now = Instant.now();
        List<ApmMetricHourly> upserts = new ArrayList<>(groups.size());
        for (List<ApmMetric> group : groups.values()) {
            if (group.isEmpty()) continue;
            ApmMetric head = group.get(0);
            int total = group.size();
            List<ApmMetric> use = group;
            if (total > MAX_VALUES_PER_GROUP) {
                // 查询已按时间升序，取尾部即为「最近的」样本
                use = group.subList(total - MAX_VALUES_PER_GROUP, total);
                log.warn("APM 聚合样本超上限 平台={} 版本={} 指标={} 总数={} 仅统计最近={}",
                        head.getPlatform(), head.getClientVer(), head.getMetricName(), total, MAX_VALUES_PER_GROUP);
            }
            double[] values = new double[use.size()];
            double sum = 0;
            for (int i = 0; i < use.size(); i++) {
                values[i] = use.get(i).getMetricValue();
                sum += values[i];
            }
            Arrays.sort(values);

            ApmMetricHourly row = hourly
                    .findByPlatformAndClientVerAndMetricHourAndMetricName(
                            head.getPlatform(), head.getClientVer(), from, head.getMetricName())
                    .orElseGet(ApmMetricHourly::new);
            row.setPlatform(head.getPlatform());
            row.setClientVer(head.getClientVer());
            row.setMetricHour(from);
            row.setMetricName(head.getMetricName());
            row.setAvgValue(sum / values.length);
            row.setP50Value(percentile(values, 0.50));
            row.setP95Value(percentile(values, 0.95));
            row.setP99Value(percentile(values, 0.99));
            row.setMaxValue(values[values.length - 1]);
            row.setSampleCount(total);
            if (row.getCreatedAt() == null) row.setCreatedAt(now);
            upserts.add(row);
        }
        hourly.saveAll(upserts);
        return upserts.size();
    }

    /** 分组键：平台 + 客户端版本 + 指标名（与小时表唯一键同序）。 */
    private static String groupKey(ApmMetric row) {
        return nz(row.getPlatform()) + "|" + nz(row.getClientVer()) + "|" + nz(row.getMetricName());
    }

    /**
     * 分位数（线性插值，输入必须已升序）。
     *
     * <p>线性插值而非「取第 k 个元素」：样本量小于 100 时后者会让 p95/p99 退化成最大值，
     * 而 APM 的很多分组只有几十个样本。</p>
     */
    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}