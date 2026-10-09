package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 指定进程的已加载模块快照（文档 §5.2）。
 *
 * @param processName 被枚举的进程名
 * @param modules     模块列表（DLL / SO）
 */
public record ModuleSnapshot(String processName, List<ModuleInfo> modules) {

    public ModuleSnapshot {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public static ModuleSnapshot empty(String processName) {
        return new ModuleSnapshot(processName, List.of());
    }

    /**
     * 单个模块。
     *
     * @param name        模块文件名（小写比较由调用方负责）
     * @param path        模块完整路径（可为 null）
     * @param baseAddress 基址（不可得置 0）
     * @param size        映像大小（不可得置 0）
     */
    public record ModuleInfo(String name, String path, long baseAddress, long size) {
    }
}