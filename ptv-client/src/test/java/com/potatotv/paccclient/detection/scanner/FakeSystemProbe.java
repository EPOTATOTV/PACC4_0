package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.MemoryScanResult;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.NetworkSnapshot;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.PathHit;
import com.potatotv.paccclient.probe.PathPattern;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.RegistryPattern;
import com.potatotv.paccclient.probe.ServiceSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.probe.UsbDevice;
import com.potatotv.paccclient.probe.WindowInfo;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 可编程假探针：测试把构造好的系统快照填进来，验检测逻辑，不碰真实系统。
 *
 * <p>默认支持 PROCESSES / MODULES / DRIVERS / SERVICES / FILES / REGISTRY / NETWORK / USB，
 * 需要测降级时从 {@link #supported} 里移除对应能力即可。</p>
 */
final class FakeSystemProbe implements SystemProbe {

    static final OsInfo WIN = new OsInfo("Windows 11", "10.0", "amd64");

    ProcessSnapshot processes = ProcessSnapshot.empty();
    ModuleSnapshot modules = ModuleSnapshot.empty("");
    DriverSnapshot drivers = DriverSnapshot.empty();
    ServiceSnapshot services = ServiceSnapshot.empty();
    List<PathHit> pathHits = List.of();
    List<RegistryHit> registryHits = List.of();
    NetworkSnapshot network = NetworkSnapshot.empty();
    List<UsbDevice> usb = List.of();
    MemoryScanResult memory = MemoryScanResult.unsupported(null, "test");
    /** 非空时按调用顺序逐条消费，用于精确模拟「部分特征码命中」。 */
    final Deque<MemoryScanResult> memoryResults = new ArrayDeque<>();
    OsInfo os = WIN;

    final Set<Capability> supported = EnumSet.of(
            Capability.PROCESSES, Capability.MODULES, Capability.DRIVERS, Capability.SERVICES,
            Capability.FILES, Capability.REGISTRY, Capability.NETWORK, Capability.USB);

    @Override
    public boolean isSupported(Capability capability) {
        return supported.contains(capability);
    }

    @Override
    public OsInfo osInfo() {
        return os;
    }

    @Override
    public ProcessSnapshot snapshotProcesses() {
        return processes;
    }

    @Override
    public ProcessSnapshot.ProcessInfo currentProcess() {
        return new ProcessSnapshot.ProcessInfo(4242, "java.exe", "C:\\java.exe", null);
    }

    @Override
    public ModuleSnapshot snapshotModules(String processName) {
        return modules;
    }

    @Override
    public MemoryScanResult scanMemory(String processName, byte[] pattern, byte[] mask) {
        return memoryResults.isEmpty() ? memory : memoryResults.poll();
    }

    @Override
    public DriverSnapshot snapshotDrivers() {
        return drivers;
    }

    @Override
    public ServiceSnapshot snapshotServices() {
        return services;
    }

    @Override
    public List<PathHit> scanPaths(List<PathPattern> patterns, List<Path> roots) {
        return pathHits;
    }

    @Override
    public List<RegistryHit> scanRegistry(List<RegistryPattern> patterns) {
        return registryHits;
    }

    @Override
    public NetworkSnapshot snapshotNetwork() {
        return network;
    }

    @Override
    public List<UsbDevice> enumerateUsb() {
        return usb;
    }

    @Override
    public List<WindowInfo> enumerateWindows() {
        return List.of();
    }
}