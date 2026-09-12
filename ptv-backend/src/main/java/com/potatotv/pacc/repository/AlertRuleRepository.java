package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {

    List<AlertRule> findAllByOrderByIdAsc();
}