package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.EffectConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EffectConfigRepository extends JpaRepository<EffectConfig, String> {

    Optional<EffectConfig> findById(String id);
}