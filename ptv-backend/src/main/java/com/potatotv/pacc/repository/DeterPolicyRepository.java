package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DeterPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeterPolicyRepository extends JpaRepository<DeterPolicy, Long> {

    List<DeterPolicy> findAllByEnabledTrueOrderByCreatedAtDesc();

    Optional<DeterPolicy> findByScopeTypeAndScopeValue(String scopeType, String scopeValue);

    long countByAction(String action);

    long countByEnabledTrue();
}