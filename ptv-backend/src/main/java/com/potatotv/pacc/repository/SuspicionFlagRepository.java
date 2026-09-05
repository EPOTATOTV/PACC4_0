package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.SuspicionFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuspicionFlagRepository extends JpaRepository<SuspicionFlag, String> {
    List<SuspicionFlag> findAllByOrderByCreatedAtDesc();
    List<SuspicionFlag> findByPteidOrderByCreatedAtDesc(String pteid);
    long countByKind(SuspicionFlag.Kind kind);
}