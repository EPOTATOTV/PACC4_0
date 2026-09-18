package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DlRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DlReleaseRepository extends JpaRepository<DlRelease, String> {

    List<DlRelease> findByEnabledTrueOrderByPlatformAscArtifactAsc();

    Optional<DlRelease> findByPlatformAndArtifactAndEnabledTrue(String platform, String artifact);
}