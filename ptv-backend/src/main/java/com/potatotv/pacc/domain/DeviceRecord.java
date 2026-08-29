package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 账号的登录设备（设备指纹）。用于玩家门户展示其登录历史设备，
 * 并标记当前正在使用的设备（active）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_device")
public class DeviceRecord {

    /** 稳定标识：由 pteid+设备指纹 派生（SHA-256 摘要）。 */
    @Id
    private String deviceId;

    private String pteid;

    /** 脱敏后的设备指纹标识（社区不出明文指纹）。 */
    @Column(length = 1024)
    private String deviceFingerprint;

    private String platform;

    private String deviceName;

    private String ip;

    private Instant firstLoginAt;

    private Instant lastLoginAt;

    /** 是否为当前正在使用的设备。 */
    @Builder.Default
    private boolean active = false;
}