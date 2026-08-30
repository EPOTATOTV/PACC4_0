package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminLoginLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminLoginLogRepository extends JpaRepository<AdminLoginLog, Long> {
    List<AdminLoginLog> findTop50ByOrderByCreatedAtDesc();
}