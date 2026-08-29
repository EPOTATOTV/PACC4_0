package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.InspectSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectSessionRepository extends JpaRepository<InspectSession, String> {

    List<InspectSession> findByStateOrderByStartedAtDesc(String state);
}