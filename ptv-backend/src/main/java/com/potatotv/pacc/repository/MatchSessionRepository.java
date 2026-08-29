package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.MatchSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchSessionRepository extends JpaRepository<MatchSession, String> {
    List<MatchSession> findAllByOrderByStartedAtDesc();
    List<MatchSession> findByPteidOrderByStartedAtDesc(String pteid);
    Optional<MatchSession> findFirstByPteidAndStatusOrderByStartedAtDesc(String pteid, MatchSession.Status status);
    Optional<MatchSession> findByMatchIdAndPteid(String matchId, String pteid);
}