package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.CheatRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CheatRecordRepository extends JpaRepository<CheatRecord, String> {

    Optional<CheatRecord> findTopByOrderByRecordHashDesc();

    Optional<CheatRecord> findFirstByAlertId(String alertId);

    Page<CheatRecord> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<CheatRecord> findByPteidOrderByOccurredAtDesc(String pteid, Pageable pageable);

    long countByRevokedFalse();

    @Query("select count(c) from CheatRecord c where c.revoked = true")
    long countRevokedTrue();

    long countByAlertIdAndRevokedFalse(String alertId);

    /** 查端误报：按告警撤销对应作弊记录（保留原始行，仅标记撤销 + 结论）。 */
    @Modifying
    @Query("update CheatRecord c set c.revoked = true, c.inspectConclusion = 'FALSE_POSITIVE' where c.alertId = :alertId")
    void revokeByAlert(@Param("alertId") String alertId);
}