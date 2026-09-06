package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, String> {

    long countByOccurredAtBetween(Instant start, Instant end);

    List<DetectionEvent> findByOccurredAtAfter(Instant start);

    @Query("select d.eventType, count(d) from DetectionEvent d where d.occurredAt between :start and :end group by d.eventType")
    List<Object[]> countByTypeBetween(@Param("start") Instant start, @Param("end") Instant end);

    /** 按自然日分组统计检测事件数，供 BI 检测趋势报表使用。 */
    @Query("select function('date', d.occurredAt), count(d) from DetectionEvent d " +
            "where d.occurredAt between :start and :end group by function('date', d.occurredAt)")
    List<Object[]> countGroupByDay(@Param("start") Instant start, @Param("end") Instant end);

    /** 按日期 + 版本分组统计，供 BI 版本对比报表使用。 */
    @Query("select function('date', d.occurredAt), d.edition, count(d) from DetectionEvent d " +
            "where d.occurredAt between :start and :end group by function('date', d.occurredAt), d.edition")
    List<Object[]> countGroupByDayAndEdition(@Param("start") Instant start, @Param("end") Instant end);

    long countByEdition(DetectionEvent.Edition edition);

    long countByEditionAndOccurredAtBetween(DetectionEvent.Edition edition, Instant start, Instant end);
}