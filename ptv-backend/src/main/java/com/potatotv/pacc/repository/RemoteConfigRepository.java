package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.RemoteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemoteConfigRepository extends JpaRepository<RemoteConfig, String> {

    List<RemoteConfig> findAllByOrderByCategoryAscIdAsc();
}