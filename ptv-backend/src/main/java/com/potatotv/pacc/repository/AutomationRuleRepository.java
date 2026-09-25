package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.automation.AutomationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationRuleRepository extends JpaRepository<AutomationRule, String> {

    Optional<AutomationRule> findByCode(String code);

    List<AutomationRule> findAllByOrderByCodeAsc();
}