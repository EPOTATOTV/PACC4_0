package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ReleaseInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<ReleaseInfo, String> {

    List<ReleaseInfo> findByPlatformAndChannelOrderByBuildNoDesc(String platform, String channel);

    List<ReleaseInfo> findAllByOrderByCreatedAtDesc();

    Optional<ReleaseInfo> findTopByPlatformAndChannelAndStatusOrderByBuildNoDesc(String platform, String channel, String status);
}