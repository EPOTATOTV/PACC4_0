package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.RedscreenAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedscreenAlertRepository extends JpaRepository<RedscreenAlert, String> {

    long countByState(String state);

    List<RedscreenAlert> findByStateOrderByOccurredAtDesc(String state);

    /** BI 实时大屏：时间窗内红屏事件数与最近红屏明细。 */
    long countByOccurredAtBetween(java.time.Instant start, java.time.Instant end);

    List<RedscreenAlert> findTop50ByOrderByOccurredAtDesc();

    long countByOccurredAtBetweenAndStateIn(
            java.time.Instant start, java.time.Instant end, java.util.Collection<String> states);

    boolean existsByPteidAndCheatTypeAndOccurredAtAfter(String pteid, String cheatType, java.time.Instant after);

    @Query("select a.cheatType, count(a) from RedscreenAlert a where a.occurredAt between :start and :end group by a.cheatType")
    List<Object[]> countGroupByCheatType(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    /** 按自然日分组统计红屏事件数，供 BI 红屏趋势报表使用。 */
    @Query("select function('date', a.occurredAt), count(a) from RedscreenAlert a " +
            "where a.occurredAt between :start and :end group by function('date', a.occurredAt)")
    List<Object[]> countGroupByDay(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    /** 按状态分组统计红屏数（如误报率：FALSE_POSITIVE 占比）。 */
    @Query("select a.state, count(a) from RedscreenAlert a " +
            "where a.occurredAt between :start and :end group by a.state")
    List<Object[]> countGroupByState(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    /** 远程解锁：仅待查验（PENDING_INSPECT）允许转为已解除，返回影响行数。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update RedscreenAlert a set a.state = :toState where a.alertId = :id and a.state = 'PENDING_INSPECT'")
    int unlockByAlertId(@Param("id") String id, @Param("toState") String toState);
}