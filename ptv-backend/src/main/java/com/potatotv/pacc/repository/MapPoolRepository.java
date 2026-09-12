package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.MapPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MapPoolRepository extends JpaRepository<MapPool, String> {
    List<MapPool> findAllByOrderByCreatedAtDesc();
    List<MapPool> findByActiveTrueOrderByCreatedAtDesc();
    List<MapPool> findByTournamentIdOrderByCreatedAtDesc(String tournamentId);
}