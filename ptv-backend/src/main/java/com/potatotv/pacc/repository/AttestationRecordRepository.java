package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AttestationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * v5.4 §3.6 远程证明记录仓库（成功/失败都落库，用于通过率与端侧排查）。
 */
public interface AttestationRecordRepository extends JpaRepository<AttestationRecord, String> {

    List<AttestationRecord> findTop100ByOrderByCreatedAtDesc();

    List<AttestationRecord> findTop100ByPteidOrderByCreatedAtDesc(String pteid);

    /** 全量通过/失败计数（用于整体通过率）。 */
    long countByStatus(AttestationRecord.Status status);

    /** 时间窗内通过/失败计数。 */
    long countByStatusAndCreatedAtAfter(AttestationRecord.Status status, Instant from);
}