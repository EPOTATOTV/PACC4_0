package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.TenantAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantAdminRepository extends JpaRepository<TenantAdmin, Long> {

    List<TenantAdmin> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<TenantAdmin> findByAdminIdentity(String adminIdentity);

    Optional<TenantAdmin> findByTenantIdAndAdminIdentity(String tenantId, String adminIdentity);

    long countByTenantId(String tenantId);

    void deleteByTenantIdAndAdminIdentity(String tenantId, String adminIdentity);
}