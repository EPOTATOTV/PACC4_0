package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.KnownGoodHash;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v5.4 §3.6 已知合法摘要注册表仓库。核验只看 active 行。
 */
public interface KnownGoodHashRepository extends JpaRepository<KnownGoodHash, String> {

    List<KnownGoodHash> findByActiveTrueOrderByCreatedAtDesc();

    boolean existsByKindAndHashAndActiveTrue(KnownGoodHash.Kind kind, String hash);
}