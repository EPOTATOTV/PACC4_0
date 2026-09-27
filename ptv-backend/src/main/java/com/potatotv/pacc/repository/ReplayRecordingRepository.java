package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ReplayRecording;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReplayRecordingRepository extends JpaRepository<ReplayRecording, String> {

    /** 某玩家的录像（最新在前）。 */
    List<ReplayRecording> findTop50ByPteidOrderByCreatedAtDesc(String pteid);

    /** 最近上传的录像（不指定玩家时使用）。 */
    List<ReplayRecording> findTop50ByOrderByCreatedAtDesc();

    /** 过期录像（保留期清理用）。 */
    List<ReplayRecording> findByExpiresAtBefore(Instant cutoff);

    long countByExpiresAtBefore(Instant cutoff);
}