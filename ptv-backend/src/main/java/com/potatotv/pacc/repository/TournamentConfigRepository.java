package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.TournamentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentConfigRepository extends JpaRepository<TournamentConfig, String> {
}