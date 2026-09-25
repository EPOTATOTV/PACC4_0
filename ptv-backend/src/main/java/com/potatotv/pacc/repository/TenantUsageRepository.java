package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.tenant.TenantUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantUsageRepository extends JpaRepository<TenantUsage, Long> {

    List<TenantUsage> findByTenantIdOrderByOccurredAtDesc(String tenantId);

    long countByTenantId(String tenantId);
}