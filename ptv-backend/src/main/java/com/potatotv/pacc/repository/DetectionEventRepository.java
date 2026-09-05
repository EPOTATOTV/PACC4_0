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

    long countByEdition(DetectionEvent.Edition edition);
}