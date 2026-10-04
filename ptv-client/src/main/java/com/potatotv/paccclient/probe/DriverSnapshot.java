package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 内核驱动快照（文档 §4.6 / §5.2）。
 *
 * @param drivers 驱动列表
 */
public record DriverSnapshot(List<DriverInfo> drivers) {

    public DriverSnapshot {
        drivers = drivers == null ? List.of() : List.copyOf(drivers);
    }

    public static DriverSnapshot empty() {
        return new DriverSnapshot(List.of());
    }

    /**
     * 单个驱动。
     *
     * @param name  驱动模块名（如 {@code dbk64} / {@code dbk64.sys}）
     * @param path  驱动文件路径（可为 null）
     * @param state 运行状态（不可得时为空串）
     */
    public record DriverInfo(String name, String path, String state) {
    }
}