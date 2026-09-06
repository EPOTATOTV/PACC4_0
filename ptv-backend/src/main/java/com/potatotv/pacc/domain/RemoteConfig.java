package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.7 远程配置：供客户端/紧急调整的键值配置，主键即 key。
 * intValue / doubleValue / boolValue 至多一个生效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_remote_config")
public class RemoteConfig {

    public enum Category { DETECTION, SCAN, REDSCREEN, THROTTLE }

    @Id
    private String id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Category category = Category.DETECTION;

    private Integer intValue;

    private Double doubleValue;

    private Boolean boolValue;

    private String updatedBy;

    @Builder.Default
    private Instant updatedAt = Instant.now();
}