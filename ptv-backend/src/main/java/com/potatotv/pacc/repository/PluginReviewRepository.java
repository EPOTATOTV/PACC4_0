package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PluginReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PluginReviewRepository extends JpaRepository<PluginReview, Long> {

    List<PluginReview> findByPluginIdOrderByCreatedAtDesc(String pluginId);
}