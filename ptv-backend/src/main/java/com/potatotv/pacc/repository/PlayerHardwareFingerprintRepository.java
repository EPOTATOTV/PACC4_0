package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * v5.2 §6.2 玩家硬件指纹历史仓库。
 */
public interface PlayerHardwareFingerprintRepository extends JpaRepository<PlayerHardwareFingerprint, Long> {

    /** 某玩家某指纹的历史行（存在即非新设备）。 */
    Optional<PlayerHardwareFingerprint> findByPteidAndFingerprintHash(String pteid, String fingerprintHash);

    /** 某玩家见过的全部指纹（按首次出现时间升序）。 */
    List<PlayerHardwareFingerprint> findByPteidOrderByFirstSeenAtAsc(String pteid);

    /** 某玩家见过的指纹数量（判断首次绑定 / 突变）。 */
    long countByPteid(String pteid);
}