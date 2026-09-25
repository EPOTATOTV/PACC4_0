package com.potatotv.pacc.domain.plugin;

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
 * §4.2.2 插件运行时登记：插件市场条目被热加载后在运行时的状态（LOADED / UNLOADED / ERROR）、
 * CPU 用量与错误计数，供管理端 {@code /api/admin/plugins/runtime} 观测。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_plugin_runtime")
public class PluginRuntime {

    public static final String ST_LOADED = "LOADED";
    public static final String ST_UNLOADED = "UNLOADED";
    public static final String ST_ERROR = "ERROR";

    @Id
    @Column(name = "plugin_id", length = 64)
    private String pluginId;

    @Builder.Default
    @Column(nullable = false, length = 128)
    private String name = "";

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String version = "1.0.0";

    /** 热加载来源（jar / 目录路径）。 */
    @Column(name = "class_path", length = 512)
    private String classPath;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String state = ST_UNLOADED;

    @Builder.Default
    @Column(name = "cpu_ms", nullable = false)
    private long cpuMs = 0;

    @Builder.Default
    @Column(name = "error_count", nullable = false)
    private int errorCount = 0;

    /** 插件声明的沙箱 API 白名单令牌（逗号分隔留痕）。 */
    @Column(name = "declared_apis", length = 512)
    private String declaredApis;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}