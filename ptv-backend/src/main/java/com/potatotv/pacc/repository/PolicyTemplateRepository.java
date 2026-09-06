package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PolicyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyTemplateRepository extends JpaRepository<PolicyTemplate, String> {

    List<PolicyTemplate> findBySceneOrderByCreatedAtDesc(String scene);

    List<PolicyTemplate> findAllByOrderByCreatedAtDesc();
}