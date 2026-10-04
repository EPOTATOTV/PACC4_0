package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 系统服务快照（文档 §4.6 / §5.2）。
 *
 * @param services 服务列表
 */
public record ServiceSnapshot(List<ServiceInfo> services) {

    public ServiceSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
    }

    public static ServiceSnapshot empty() {
        return new ServiceSnapshot(List.of());
    }

    /**
     * 单个服务。
     *
     * @param name        服务名（如 {@code CheatEngine}）
     * @param displayName 显示名
     * @param state       运行状态（Running / Stopped 等）
     */
    public record ServiceInfo(String name, String displayName, String state) {
    }
}