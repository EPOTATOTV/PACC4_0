package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.CheatHardwareProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CheatHardwareProfileRepository extends JpaRepository<CheatHardwareProfile, String> {

    List<CheatHardwareProfile> findByActiveTrueOrderByRiskScoreDesc();
}