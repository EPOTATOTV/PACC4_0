package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.MapBanPickSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MapBanPickSessionRepository extends JpaRepository<MapBanPickSession, String> {
    List<MapBanPickSession> findAllByOrderByCreatedAtDesc();
    List<MapBanPickSession> findByStatusOrderByCreatedAtDesc(MapBanPickSession.Status status);
    List<MapBanPickSession> findByTournamentIdOrderByCreatedAtDesc(String tournamentId);
    List<MapBanPickSession> findByPoolIdOrderByCreatedAtDesc(String poolId);
    Optional<MapBanPickSession> findByMatchId(String matchId);
    Optional<MapBanPickSession> findFirstByStatusInOrderByCreatedAtDesc(List<MapBanPickSession.Status> statuses);
}