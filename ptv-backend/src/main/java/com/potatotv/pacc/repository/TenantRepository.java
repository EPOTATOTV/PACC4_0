package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, String> {
    Optional<Tenant> findFirstByTenantId(String tenantId);
    List<Tenant> findByStatusOrderByCreatedAtDesc(String status);
    long countByPlan(String plan);
    Page<Tenant> findByNameContainingIgnoreCaseOrTenantIdContainingIgnoreCase(String name, String tenantId, Pageable pageable);
    Page<Tenant> findByTenantIdIn(List<String> ids, Pageable pageable);
}