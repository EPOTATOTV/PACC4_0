package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * 底层检测探针接口：内存 / 进程 / 设备维度。
 * 具体实现按平台接入（Windows/CDP 内核驱动、Linux Module、Harmony 用户态，见 platform 模块）。
 * 本演示提供基于结构签名的简化实现。
 */
public interface LowLevelProbe {

    /**
     * 扫描当前进程的关键内存区与已有进程列表，返回命中的检测事件（若有）。
     */
    Optional<DetectionEvent> scan();
}