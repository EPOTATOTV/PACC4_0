package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.BiDashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BiDashboardRepository extends JpaRepository<BiDashboard, Long> {

    List<BiDashboard> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}