package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    Optional<ApiKey> findFirstByKeyId(String keyId);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}