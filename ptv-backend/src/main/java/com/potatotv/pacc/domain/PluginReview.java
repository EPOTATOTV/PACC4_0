package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.0 插件市场：插件审核留痕（平台管理员操作，不可篡改）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_plugin_review")
public class PluginReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String pluginId;

    @Column(nullable = false, length = 64)
    private String reviewer;

    /** APPROVED / REJECTED / REMOVED */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(length = 1000)
    private String comment;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}