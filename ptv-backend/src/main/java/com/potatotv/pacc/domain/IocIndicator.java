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
 * v4.7 IOC 中心化：从威胁情报样本自动分析/静态指纹抽取的可观测指标（文件哈希、外挂特征字符串、IP/URL 等）。
 * 支持检索、订阅告警、处置（DISARM 误报 / EXPIRED 过期）与检测阈值联动。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_ioc_indicator", indexes = {
        @Index(name = "idx_ioc_value", columnList = "value"),
        @Index(name = "idx_ioc_type", columnList = "type"),
        @Index(name = "idx_ioc_state", columnList = "state")
})
public class IocIndicator {

    public enum IocType {
        FILE_HASH, STRING, IP, URL, CLIENT_FAMILY
    }

    public enum State { OPEN, DISARMED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IOC 值（哈希 / 特征串 / IP / URL / 客户端家族名）。 */
    @Column(nullable = false, length = 255)
    private String value;

    @Column(nullable = false, length = 16)
    private String type;

    /** 来源威胁样本 id。 */
    private String sourceId;

    private String sourceFamily;

    @Builder.Default
    @Column(nullable = false)
    private int severity = 3;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String state = "OPEN";

    @Builder.Default
    @Column(nullable = false)
    private boolean subscribed = false;

    /** 命中达到该次数（演示：手动命中）可触发告警。 */
    @Builder.Default
    @Column(nullable = false)
    private int alertThreshold = 3;

    @Builder.Default
    @Column(nullable = false)
    private long hitCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Instant firstSeen = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant lastSeen = Instant.now();
}