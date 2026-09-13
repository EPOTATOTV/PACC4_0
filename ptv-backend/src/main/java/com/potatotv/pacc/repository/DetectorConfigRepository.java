package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DetectorConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DetectorConfigRepository extends JpaRepository<DetectorConfig, String> {

    Optional<DetectorConfig> findByDetectorKey(String detectorKey);

    java.util.List<DetectorConfig> findAllByOrderByDetectorKeyAsc();
}