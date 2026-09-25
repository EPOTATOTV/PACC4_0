package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * 底层检测：内核内存特征 + 注入进程 / 提权设备探测。
 *
 * <p><b>v5.2 变更</b>：旧实现以 {@code Math.random() < 0.02} 随机产出「命中」用于联调，
 * 该演示路径已删除——随机命中会在真实玩家机上按心跳持续误报，必须由真实后端驱动。
 * 真实扫描经内核驱动（{@code platform/kernel-windows}、{@code platform/kernel-linux}）
 * 或原生桥接完成，结论走 Java Agent 的 findings 通道（{@link JavaAgentProbe}）
 * 与查端取证通道进入端侧链路；没有后端时本探针安静返回空。</p>
 *
 * <p>特征库样例（KillAura / Fly 等结构签名）不在本类维护：生产由 PTV 灰度下发，
 * 客户端侧落在 {@code signature.SignatureSync}（{@code /api/player/ops/signatures}）。</p>
 */
public final class KernelMemoryProbe implements LowLevelProbe {

    /** 是否接入了真实扫描后端（内核驱动 / 原生桥接）。 */
    private final boolean backendAvailable;

    public KernelMemoryProbe() {
        this(false);
    }

    /**
     * @param backendAvailable 由平台层在检测到内核驱动 / 原生桥接可用时置 {@code true}
     */
    public KernelMemoryProbe(boolean backendAvailable) {
        this.backendAvailable = backendAvailable;
    }

    /**
     * @return 接入真实后端时返回后端结论；当前 Java 客户端不带内核驱动，恒返回空
     */
    @Override
    public Optional<DetectionEvent> scan() {
        if (!backendAvailable) return Optional.empty();
        // 真实内存扫描（读取受保护进程内存 + 特征码比对）由 platform 层的驱动/桥接实现；
        // Java 侧不复制该逻辑，避免出现「看起来在扫、实际靠猜」的假阳性来源。
        return Optional.empty();
    }
}