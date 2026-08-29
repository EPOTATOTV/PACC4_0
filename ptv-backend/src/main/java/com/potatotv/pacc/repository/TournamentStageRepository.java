package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.TournamentStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TournamentStageRepository extends JpaRepository<TournamentStage, String> {
    List<TournamentStage> findByTournamentIdOrderByOrderNoAsc(String tournamentId);
}