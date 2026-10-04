package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 进程快照（文档 §5.2）。
 *
 * @param processes 进程列表（只含进程名/路径/窗口标题等元数据，不读进程内存）
 */
public record ProcessSnapshot(List<ProcessInfo> processes) {

    public ProcessSnapshot {
        processes = processes == null ? List.of() : List.copyOf(processes);
    }

    public static ProcessSnapshot empty() {
        return new ProcessSnapshot(List.of());
    }

    /**
     * 单个进程信息。
     *
     * @param pid         进程号
     * @param name        进程名（可执行文件名；不可得时为空串）
     * @param path        可执行文件路径（可为 null）
     * @param windowTitle 主窗口标题（仅 Windows 且可用时非空，其余为 null）
     */
    public record ProcessInfo(int pid, String name, String path, String windowTitle) {
    }
}