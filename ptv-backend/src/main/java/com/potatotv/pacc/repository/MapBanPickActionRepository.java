package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.MapBanPickAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MapBanPickActionRepository extends JpaRepository<MapBanPickAction, String> {
    List<MapBanPickAction> findByBpSessionIdOrderByRoundNoAscCreatedAtAsc(String bpSessionId);
}