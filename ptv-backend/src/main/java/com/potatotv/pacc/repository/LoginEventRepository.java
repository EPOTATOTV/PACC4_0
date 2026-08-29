package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {
    List<LoginEvent> findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(String pteid, Instant after);
    List<LoginEvent> findByPteid(String pteid);
}