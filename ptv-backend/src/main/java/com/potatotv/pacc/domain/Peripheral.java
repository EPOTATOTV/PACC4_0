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
 * 账号在绑定设备上使用过的外设（鼠标/键盘/耳机/手柄/USB 存储/投屏器等）。
 * connected 表示该外设当前正在使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_peripheral")
public class Peripheral {

    @Id
    private String peripheralId;

    private String pteid;

    /** 归属设备的指纹标识（与 DeviceRecord.deviceFingerprint 对应）。 */
    @Column(length = 1024)
    private String deviceFingerprint;

    /** mouse / keyboard / headset / gamepad / usb_storage / dongle / monitor 等。 */
    private String kind;

    private String vendor;

    private String model;

    /** 当前是否正在使用。 */
    @Builder.Default
    private boolean connected = false;

    @Builder.Default
    private Instant firstSeenAt = Instant.now();

    private Instant lastSeenAt;
}