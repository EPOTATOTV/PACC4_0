package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ClientCrashReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ClientCrashReportRepository extends JpaRepository<ClientCrashReport, String> {

    long countByCreatedAtAfter(Instant since);

    List<ClientCrashReport> findTop200ByOrderByCreatedAtDesc();
}