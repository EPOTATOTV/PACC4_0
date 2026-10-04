package com.potatotv.paccclient.probe;

import java.nio.file.Path;
import java.util.List;

/**
 * 系统探针统一接口（文档 §5.2）。
 *
 * <p>所有系统级数据采集（进程 / 模块 / 内存 / 驱动 / 服务 / 文件 / 注册表 / 网络 / USB / 窗口）
 * 收敛到这一个接口，核心检测器与插件共用。实现必须遵守两条约定：</p>
 * <ul>
 *   <li><b>绝不抛出</b>：能力不可用时返回空快照并让 {@link #isSupported} 返回 false，
 *       由调用方优雅降级（文档 §9 注意事项 2）。</li>
 *   <li><b>不读内容</b>：只采集检测必需的元数据（进程名 / 模块名 / 文件路径 / 注册表键名），
 *       不读文件内容、不读注册表键值（文档 §9 注意事项 3）。</li>
 * </ul>
 *
 * <p>默认实现：{@link PortableSystemProbe}（纯 JDK，跨平台可用子集）、
 * {@link WindowsSystemProbe}（Windows 全能力，模块 / 内存走 PaccManager 回环探针）。</p>
 */
public interface SystemProbe {

    /** 探针能力项（文档 §5.3 各平台能力差异）。 */
    enum Capability {
        PROCESSES,
        MODULES,
        MEMORY,
        DRIVERS,
        SERVICES,
        FILES,
        REGISTRY,
        NETWORK,
        USB,
        WINDOWS
    }

    /** 当前平台 / 运行环境是否支持该能力。 */
    boolean isSupported(Capability capability);

    /** 本机系统信息。 */
    OsInfo osInfo();

    // ---- 进程 ----

    ProcessSnapshot snapshotProcesses();

    ProcessSnapshot.ProcessInfo currentProcess();

    // ---- 模块（指定进程的已加载 DLL / SO） ----

    ModuleSnapshot snapshotModules(String processName);

    // ---- 内存（用户态扫描，需 PaccManager 原生协助） ----

    MemoryScanResult scanMemory(String processName, byte[] pattern, byte[] mask);

    // ---- 驱动与服务 ----

    DriverSnapshot snapshotDrivers();

    ServiceSnapshot snapshotServices();

    // ---- 文件 ----

    List<PathHit> scanPaths(List<PathPattern> patterns, List<Path> roots);

    // ---- 注册表（仅 Windows） ----

    List<RegistryHit> scanRegistry(List<RegistryPattern> patterns);

    // ---- 网络 ----

    NetworkSnapshot snapshotNetwork();

    // ---- USB 设备 ----

    List<UsbDevice> enumerateUsb();

    // ---- 窗口 ----

    List<WindowInfo> enumerateWindows();
}