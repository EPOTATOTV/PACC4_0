package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.alert.AlertSuppressionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertSuppressionRuleRepository extends JpaRepository<AlertSuppressionRule, String> {

    List<AlertSuppressionRule> findByEnabledTrue();
}