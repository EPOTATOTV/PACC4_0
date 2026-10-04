package com.potatotv.paccclient.probe;

/**
 * {@link SystemProbe} 工厂（文档 §5.3）：按当前平台选择实现。
 *
 * <p>Windows 用 {@link WindowsSystemProbe}（全能力，模块 / 内存走 PaccManager 回环探针），
 * 其余平台退回 {@link PortableSystemProbe}（纯 JDK 的进程 / 文件子集，其余能力经
 * {@link SystemProbe#isSupported} 报 false 由检测器优雅降级）。</p>
 */
public final class SystemProbes {

    private SystemProbes() {
    }

    /** 按当前操作系统创建探针。 */
    public static SystemProbe create() {
        OsInfo os = OsInfo.current();
        return os.isWindows() ? new WindowsSystemProbe() : new PortableSystemProbe(os);
    }
}