package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.TournamentNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TournamentNoticeRepository extends JpaRepository<TournamentNotice, String> {
    List<TournamentNotice> findByTournamentIdOrderByCreatedAtDesc(String tournamentId);
}