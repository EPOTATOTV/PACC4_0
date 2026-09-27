package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.tenant.TenantDetectionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * §4.2.3 租户检测记录仓储。所有查询方法都以 tenantId 为条件强制租户隔离（验收 A25）。
 */
@Repository
public interface TenantDetectionRecordRepository extends JpaRepository<TenantDetectionRecord, String> {

    Page<TenantDetectionRecord> findByTenantId(String tenantId, Pageable pageable);

    Optional<TenantDetectionRecord> findByIdAndTenantId(String id, String tenantId);

    long countByTenantId(String tenantId);
}