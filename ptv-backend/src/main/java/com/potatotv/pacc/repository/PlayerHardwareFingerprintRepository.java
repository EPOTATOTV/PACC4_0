package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
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

    /** v5.3 管理端：按最近出现时间倒序取指纹明细（限 300 条）。 */
    List<PlayerHardwareFingerprint> findTop300ByOrderByLastSeenAtDesc();

    /** v5.3 管理端：某玩家的指纹明细（按最近出现时间倒序）。 */
    List<PlayerHardwareFingerprint> findTop300ByPteidOrderByLastSeenAtDesc(String pteid);

    /** v5.3 管理端：一批指纹摘要的全部持有者（用于判断多账号共用同一设备）。 */
    List<PlayerHardwareFingerprint> findByFingerprintHashIn(Collection<String> fingerprintHashes);

    /** v5.3 管理端：过手多枚指纹的玩家（设备突变标记），一次查询拿下。 */
    @Query("select f.pteid from PlayerHardwareFingerprint f group by f.pteid having count(f) > 1")
    List<String> findMutationPteids();
}