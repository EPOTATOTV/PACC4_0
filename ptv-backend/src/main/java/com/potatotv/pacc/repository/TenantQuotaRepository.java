package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.tenant.TenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantQuotaRepository extends JpaRepository<TenantQuota, String> {
}