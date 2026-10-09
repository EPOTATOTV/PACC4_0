package com.potatotv.paccclient.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 纯 JDK 系统探针（跨平台可用子集）。
 *
 * <p>提供两项通用能力：进程枚举（{@link ProcessHandle}）与文件路径扫描（{@link Files#walk}）。
 * 模块 / 内存 / 驱动 / 服务 / 注册表 / 网络 / USB / 窗口在纯 JDK 下不可得，一律返回空快照并让
 * {@link #isSupported} 返回 false，由检测器优雅降级（文档 §9 注意事项 2）。</p>
 *
 * <p>文件扫描只做有界遍历（深度与条目数双封顶），且只做路径名匹配 —— 不打开、不读取文件内容。</p>
 */
public class PortableSystemProbe implements SystemProbe {

    /** 每个根目录的最大遍历深度。 */
    private static final int MAX_DEPTH = 4;
    /** 每个根目录的最大访问条目数，防止超大目录拖慢客户端。 */
    private static final int MAX_ENTRIES = 20_000;

    private final OsInfo osInfo;

    public PortableSystemProbe() {
        this(OsInfo.current());
    }

    public PortableSystemProbe(OsInfo osInfo) {
        this.osInfo = osInfo == null ? OsInfo.current() : osInfo;
    }

    @Override
    public boolean isSupported(Capability capability) {
        return capability == Capability.PROCESSES || capability == Capability.FILES;
    }

    @Override
    public OsInfo osInfo() {
        return osInfo;
    }

    @Override
    public ProcessSnapshot snapshotProcesses() {
        List<ProcessSnapshot.ProcessInfo> list = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(handle -> {
                ProcessSnapshot.ProcessInfo info = toProcessInfo(handle);
                if (info != null) {
                    list.add(info);
                }
            });
        } catch (RuntimeException ignored) {
            // 平台不支持进程枚举：返回空快照
        }
        return new ProcessSnapshot(list);
    }

    @Override
    public ProcessSnapshot.ProcessInfo currentProcess() {
        ProcessSnapshot.ProcessInfo info = toProcessInfo(ProcessHandle.current());
        return info == null ? new ProcessSnapshot.ProcessInfo((int) ProcessHandle.current().pid(), "", null, null) : info;
    }

    @Override
    public ModuleSnapshot snapshotModules(String processName) {
        return ModuleSnapshot.empty(processName);
    }

    @Override
    public MemoryScanResult scanMemory(String processName, byte[] pattern, byte[] mask) {
        return MemoryScanResult.unsupported(null, "用户态内存扫描需要 PaccManager 原生探针");
    }

    @Override
    public DriverSnapshot snapshotDrivers() {
        return DriverSnapshot.empty();
    }

    @Override
    public ServiceSnapshot snapshotServices() {
        return ServiceSnapshot.empty();
    }

    @Override
    public List<PathHit> scanPaths(List<PathPattern> patterns, List<Path> roots) {
        if (patterns == null || patterns.isEmpty() || roots == null || roots.isEmpty()) {
            return List.of();
        }
        List<PathHit> hits = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            scanRoot(patterns, root, hits);
        }
        return hits;
    }

    @Override
    public List<RegistryHit> scanRegistry(List<RegistryPattern> patterns) {
        return List.of();
    }

    @Override
    public NetworkSnapshot snapshotNetwork() {
        return NetworkSnapshot.empty();
    }

    @Override
    public List<UsbDevice> enumerateUsb() {
        return List.of();
    }

    @Override
    public List<WindowInfo> enumerateWindows() {
        return List.of();
    }

    // ------------------------------------------------------------------ 内部

    private static void scanRoot(List<PathPattern> patterns, Path root, List<PathHit> hits) {
        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            Iterator<Path> it = stream.iterator();
            int visited = 0;
            while (it.hasNext() && visited < MAX_ENTRIES) {
                Path path = it.next();
                visited++;
                String text = path.toString();
                for (PathPattern pattern : patterns) {
                    if (GlobMatcher.matches(pattern.glob(), text)) {
                        hits.add(new PathHit(text, pattern.weight()));
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 目录不可读 / 遍历中断：跳过该根目录
        }
    }

    private static ProcessSnapshot.ProcessInfo toProcessInfo(ProcessHandle handle) {
        try {
            String command = handle.info().command().orElse(null);
            String name = command == null ? "" : fileName(command);
            return new ProcessSnapshot.ProcessInfo((int) handle.pid(), name, command, null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String fileName(String command) {
        try {
            Path name = Path.of(command).getFileName();
            return name == null ? command : name.toString();
        } catch (RuntimeException ignored) {
            return command;
        }
    }
}