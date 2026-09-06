package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AbExperiment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AbExperimentRepository extends JpaRepository<AbExperiment, String> {

    List<AbExperiment> findAllByOrderByCreatedAtDesc();
}