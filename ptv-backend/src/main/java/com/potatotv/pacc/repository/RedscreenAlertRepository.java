package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.RedscreenAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedscreenAlertRepository extends JpaRepository<RedscreenAlert, String> {

    long countByState(String state);

    List<RedscreenAlert> findByStateOrderByOccurredAtDesc(String state);

    long countByOccurredAtBetweenAndStateIn(
            java.time.Instant start, java.time.Instant end, java.util.Collection<String> states);

    boolean existsByPteidAndCheatTypeAndOccurredAtAfter(String pteid, String cheatType, java.time.Instant after);

    @Query("select a.cheatType, count(a) from RedscreenAlert a where a.occurredAt between :start and :end group by a.cheatType")
    List<Object[]> countGroupByCheatType(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
}