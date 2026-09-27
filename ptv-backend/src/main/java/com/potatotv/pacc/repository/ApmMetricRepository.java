package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ApmMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * v5.4 APM 原始采样仓储。
 *
 * <p>注意：原始表是 APM 里唯一会持续膨胀的表，任何「按时间窗取明细」的查询都必须由调用方
 * 自己把窗口收窄（建议不超过 1 小时量级），仓储层不做隐式截断——这里只保证时间范围走索引。</p>
 */
public interface ApmMetricRepository extends JpaRepository<ApmMetric, Long> {

    /**
     * 时间窗内的原始采样，按采样时间升序。
     *
     * <p>{@code from} 含、{@code to} 不含，与小时聚合的桶边界一致（[hour, hour+1)）。
     * 调用方必须自行限定窗口宽度，否则可能一次拉回整天的全量采样。</p>
     */
    @Query("SELECT m FROM ApmMetric m WHERE m.metricTime >= :from AND m.metricTime < :to "
            + "ORDER BY m.metricTime ASC")
    List<ApmMetric> findByMetricTimeBetweenOrderByMetricTimeAsc(@Param("from") Instant from,
                                                                @Param("to") Instant to);

    /** 时间窗内的采样条数（[from, to)）。 */
    long countByMetricTimeBetween(Instant from, Instant to);

    /** 时间窗内上报过数据的客户端平台（管理端筛选下拉用）。 */
    @Query("SELECT DISTINCT m.platform FROM ApmMetric m WHERE m.metricTime >= :from")
    List<String> findDistinctPlatforms(@Param("from") Instant from);

    /** 时间窗内上报过数据的客户端版本。 */
    @Query("SELECT DISTINCT m.clientVer FROM ApmMetric m WHERE m.metricTime >= :from")
    List<String> findDistinctClientVersions(@Param("from") Instant from);

    /**
     * 时间窗内活跃客户端数（去重 pteid）。
     *
     * <p>用 COUNT(DISTINCT) 而不是把原始行拉回来在内存里去重：24 小时的原始行数量级远大于客户端数。</p>
     */
    @Query("SELECT COUNT(DISTINCT m.pteid) FROM ApmMetric m WHERE m.metricTime >= :from AND m.metricTime < :to")
    long countDistinctPteidBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** 清理保留期外的原始采样，返回删除条数（由小时聚合任务每日触发）。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM ApmMetric m WHERE m.metricTime < :cutoff")
    int deleteByMetricTimeBefore(@Param("cutoff") Instant cutoff);
}