package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ApiUsageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, String> {

    long countByApiKeyIdAndCreatedAtAfter(String apiKeyId, Instant after);

    Page<ApiUsageLog> findByApiKeyIdOrderByCreatedAtDesc(String apiKeyId, Pageable pageable);

    Page<ApiUsageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByTenantIdAndCreatedAtAfter(String tenantId, Instant after);
}