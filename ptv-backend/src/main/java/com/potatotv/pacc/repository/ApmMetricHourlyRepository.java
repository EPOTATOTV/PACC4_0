package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ApmMetricHourly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * v5.4 APM 小时聚合仓储。
 *
 * <p>三个趋势重载（仅指标 / 指标+平台 / 指标+平台+版本）刻意写成显式方法而不是
 * 「可为空的 platform 参数」：Hibernate 6 对 {@code (:param IS NULL OR ...)} 的参数类型推断在
 * 不同方言下表现不一致，显式重载更稳。告警/异常这类需要对「空平台」做哨兵判断的查询才用
 * {@code (:platform = '' OR ...)}。</p>
 */
public interface ApmMetricHourlyRepository extends JpaRepository<ApmMetricHourly, Long> {

    /** 按指标取时间窗内的小时行（[from, to)）。 */
    List<ApmMetricHourly> findByMetricNameAndMetricHourBetweenOrderByMetricHourAsc(
            String metricName, Instant from, Instant to);

    /** 按指标 + 平台取时间窗内的小时行。 */
    List<ApmMetricHourly> findByMetricNameAndPlatformAndMetricHourBetweenOrderByMetricHourAsc(
            String metricName, String platform, Instant from, Instant to);

    /** 按指标 + 平台 + 客户端版本取时间窗内的小时行。 */
    List<ApmMetricHourly> findByMetricNameAndPlatformAndClientVerAndMetricHourBetweenOrderByMetricHourAsc(
            String metricName, String platform, String clientVer, Instant from, Instant to);

    /** 唯一键定位（聚合 upsert 用：命中则更新，未命中则新建）。 */
    Optional<ApmMetricHourly> findByPlatformAndClientVerAndMetricHourAndMetricName(
            String platform, String clientVer, Instant metricHour, String metricName);

    /** 某小时的整桶小时行（告警评估按 平台+版本 桶逐行判定）。 */
    List<ApmMetricHourly> findByMetricHourBetween(Instant from, Instant to);

    /**
     * 指标历史（异常检测用）：从 {@code from} 起的全部小时行。
     * {@code platform} 传空串表示不限平台（哨兵写法，避免可空参数）。
     */
    @Query("SELECT h FROM ApmMetricHourly h WHERE h.metricName = :metric AND h.metricHour >= :from "
            + "AND (:platform = '' OR h.platform = :platform) ORDER BY h.metricHour ASC")
    List<ApmMetricHourly> findHistoryByMetric(@Param("metric") String metric,
                                              @Param("platform") String platform,
                                              @Param("from") Instant from);

    /** 时间窗内的样本总数（概览卡片口径）。 */
    @Query("SELECT COALESCE(SUM(h.sampleCount), 0) FROM ApmMetricHourly h "
            + "WHERE h.metricHour >= :from AND h.metricHour < :to")
    long sumSampleCountBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** 某指标在时间窗内出现过的客户端版本（平台传空串表示不限）。 */
    @Query("SELECT DISTINCT h.clientVer FROM ApmMetricHourly h WHERE h.metricName = :metric "
            + "AND (:platform = '' OR h.platform = :platform) ORDER BY h.clientVer")
    List<String> findVersionsForMetric(@Param("metric") String metric,
                                       @Param("platform") String platform);
}