package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEvent, String> {

    List<AlertEvent> findByStatusOrderByFiredAtDesc(String status);

    List<AlertEvent> findTop50ByOrderByFiredAtDesc();

    long countByStatus(String status);

    List<AlertEvent> findByRuleIdAndStatus(String ruleId, String status);
}
