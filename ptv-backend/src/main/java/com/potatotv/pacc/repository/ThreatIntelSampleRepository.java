package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ThreatIntelSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ThreatIntelSampleRepository extends JpaRepository<ThreatIntelSample, String> {

    List<ThreatIntelSample> findByStatusOrderByCreatedAtDesc(ThreatIntelSample.Status status);

    List<ThreatIntelSample> findTop50ByOrderByCreatedAtDesc();

    long countByStatus(ThreatIntelSample.Status status);

    List<ThreatIntelSample> findByFamily(String family);

    List<ThreatIntelSample> findByFamilyLabelIsNotNullOrderByCreatedAtDesc();
}