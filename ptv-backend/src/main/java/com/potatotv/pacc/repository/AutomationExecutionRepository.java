package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, Long> {

    Page<AutomationExecution> findAllByOrderByExecutedAtDesc(Pageable pageable);
}