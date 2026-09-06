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
 * v5.0 插件市场：插件评分与评论。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_plugin_comment")
public class PluginComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String pluginId;

    @Column(nullable = false, length = 64)
    private String author;

    /** 1-5 分。 */
    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}