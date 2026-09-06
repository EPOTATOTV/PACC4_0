package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ClientTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientTelemetryRepository extends JpaRepository<ClientTelemetry, String> {

    List<ClientTelemetry> findTop2000ByOrderByCreatedAtDesc();
}