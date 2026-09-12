package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerNotifRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerNotifReadRepository extends JpaRepository<PlayerNotifRead, String> {

    List<PlayerNotifRead> findByPteid(String pteid);
}