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

/** 单次玩家登录事件（设备指纹 + 来源 IP 封装），作为代练/共享/宏观风控的聚合底座。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_login_event", indexes = {
        @Index(name = "idx_login_pteid", columnList = "pteid"),
        @Index(name = "idx_login_ip", columnList = "ip")
})
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String pteid;

    /** 本次登录所用设备的脱敏指纹标识。 */
    @Column(length = 1024)
    private String deviceFingerprint;

    /** 来源客户端 IP。 */
    @Column(length = 64)
    private String ip;

    @Builder.Default
    private Instant createdAt = Instant.now();
}