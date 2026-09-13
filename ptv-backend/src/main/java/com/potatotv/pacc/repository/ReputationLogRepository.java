package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ReputationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReputationLogRepository extends JpaRepository<ReputationLog, String> {

    List<ReputationLog> findByPteidOrderByCreatedAtDesc(String pteid);

    List<ReputationLog> findByPteidAndCreatedAtAfterOrderByCreatedAtAsc(String pteid, Instant start);

    long countBySourceAndCreatedAtAfter(String source, Instant start);
}