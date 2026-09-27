package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.automation.AutomationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutomationStateRepository extends JpaRepository<AutomationState, String> {
}