package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.2 §6.2 玩家硬件指纹历史：记录某玩家见过的所有硬件指纹摘要及其出现次数。
 *
 * <p>只存摘要（{@code fingerprint_hash}），不落明文指纹。同一玩家出现<b>新的</b>指纹即视为
 * 「设备指纹突变」，标记可疑（见 {@code BehaviorProfileService#deviceFingerprintSeen}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_player_hardware_fingerprint", indexes = {
        @Index(name = "uk_player_fingerprint", columnList = "pteid,fingerprint_hash", unique = true),
        @Index(name = "idx_player_fingerprint_pteid", columnList = "pteid")
})
public class PlayerHardwareFingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "pteid", nullable = false, length = 64)
    private String pteid = "";

    @Builder.Default
    @Column(name = "fingerprint_hash", nullable = false, length = 128)
    private String fingerprintHash = "";

    @Builder.Default
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Builder.Default
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Builder.Default
    @Column(name = "seen_count", nullable = false)
    private long seenCount = 1L;
}