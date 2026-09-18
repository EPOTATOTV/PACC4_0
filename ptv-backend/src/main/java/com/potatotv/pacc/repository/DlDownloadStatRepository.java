package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DlDownloadStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DlDownloadStatRepository extends JpaRepository<DlDownloadStat, Long> {

    Optional<DlDownloadStat> findByDayDateAndPlatformAndArtifact(LocalDate day, String platform, String artifact);

    List<DlDownloadStat> findByDayDateGreaterThanEqual(LocalDate day);
}