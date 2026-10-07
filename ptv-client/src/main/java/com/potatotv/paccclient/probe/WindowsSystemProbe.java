package com.potatotv.paccclient.probe;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.stealth.OsCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 系统探针（文档 §5.3）：进程 / 文件走纯 JDK，其余走本机命令与 PaccManager 回环探针。
 *
 * <p>能力分工：</p>
 * <ul>
 *   <li>进程 / 窗口标题：{@link ProcessHandle} + {@code tasklist /v}（窗口标题带 2s TTL 缓存）；</li>
 *   <li>驱动：{@code driverquery}；服务：PowerShell {@code Get-Service}；</li>
 *   <li>注册表：{@code reg query}（只判键是否存在，不读键值）；</li>
 *   <li>网络：{@code netstat -ano}（本地连接表只读采样）；</li>
 *   <li>USB：PowerShell {@code Get-CimInstance Win32_PnPEntity}（只取 VID/PID 与描述）；</li>
 *   <li>模块 / 内存：{@link PaccProbeClient} 回环调用 PaccManager 原生探针。</li>
 * </ul>
 *
 * <p>命令不可用或返回非 0 时一律降级为空快照，并让 {@link #isSupported} 反映真实能力；
 * 所有命令都经 {@link OsCommand}（直接 argv、不经 shell、3s 超时、512KB 上限）。</p>
 */
public final class WindowsSystemProbe extends PortableSystemProbe {

    /** 进程 / 窗口标题缓存 TTL：多个检测器都会取进程列表，避免每次都跑 tasklist。 */
    private static final long PROCESS_CACHE_TTL_MS = 2_000L;

    private static final Pattern VID_PID = Pattern.compile("VID_[0-9A-Fa-f]{4}&PID_[0-9A-Fa-f]{4}");

    private final PaccProbeClient client;
    private final Object processLock = new Object();
    private volatile ProcessSnapshot cachedProcesses = ProcessSnapshot.empty();
    private volatile long processCacheAt;

    public WindowsSystemProbe() {
        this(new PaccProbeClient());
    }

    public WindowsSystemProbe(PaccProbeClient client) {
        this.client = client == null ? new PaccProbeClient() : client;
    }

    @Override
    public boolean isSupported(SystemProbe.Capability capability) {
        return switch (capability) {
            case PROCESSES, FILES, DRIVERS, SERVICES, REGISTRY, NETWORK, USB, WINDOWS -> true;
            case MODULES, MEMORY, SIGNATURE_VERIFY, INJECTION -> client.reachable();
            // 内核级状态需要驱动配合，用户态探针一律不支持（文档 §7 风险表）
            case KERNEL -> false;
        };
    }

    // ------------------------------------------------------------------ 进程 / 窗口

    @Override
    public ProcessSnapshot snapshotProcesses() {
        long now = System.currentTimeMillis();
        if (now - processCacheAt < PROCESS_CACHE_TTL_MS) {
            return cachedProcesses;
        }
        synchronized (processLock) {
            now = System.currentTimeMillis();
            if (now - processCacheAt < PROCESS_CACHE_TTL_MS) {
                return cachedProcesses;
            }
            ProcessSnapshot fresh = buildProcessSnapshot();
            cachedProcesses = fresh;
            processCacheAt = now;
            return fresh;
        }
    }

    @Override
    public List<WindowInfo> enumerateWindows() {
        List<WindowInfo> out = new ArrayList<>();
        for (ProcessSnapshot.ProcessInfo p : snapshotProcesses().processes()) {
            if (p.windowTitle() != null && !p.windowTitle().isBlank()) {
                out.add(new WindowInfo(p.windowTitle(), p.name(), p.pid()));
            }
        }
        return out;
    }

    private ProcessSnapshot buildProcessSnapshot() {
        ProcessSnapshot base = super.snapshotProcesses();
        Map<Integer, String> titles = windowTitlesByPid();
        List<ProcessSnapshot.ProcessInfo> out = new ArrayList<>(base.processes().size());
        for (ProcessSnapshot.ProcessInfo p : base.processes()) {
            String title = titles.get(p.pid());
            out.add(title == null ? p : new ProcessSnapshot.ProcessInfo(p.pid(), p.name(), p.path(), title));
        }
        return new ProcessSnapshot(out);
    }

    /** 解析 {@code tasklist /v /fo csv /nh} 的「PID → 窗口标题」映射。 */
    private Map<Integer, String> windowTitlesByPid() {
        Map<Integer, String> map = new HashMap<>();
        Optional<String> out = OsCommand.output("tasklist", "/v", "/fo", "csv", "/nh");
        if (out.isEmpty()) {
            return map;
        }
        for (String line : out.get().split("\\R")) {
            List<String> cols = parseCsvLine(line);
            if (cols.size() < 9) {
                continue;
            }
            Integer pid = toInt(cols.get(1).trim());
            String title = cols.get(8).trim();
            if (pid != null && !title.isBlank() && !"N/A".equalsIgnoreCase(title)) {
                map.put(pid, title);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------ 驱动 / 服务

    @Override
    public DriverSnapshot snapshotDrivers() {
        Optional<String> out = OsCommand.output("driverquery", "/fo", "csv", "/nh");
        if (out.isEmpty()) {
            return DriverSnapshot.empty();
        }
        List<DriverSnapshot.DriverInfo> list = new ArrayList<>();
        for (String line : out.get().split("\\R")) {
            List<String> cols = parseCsvLine(line);
            if (cols.isEmpty()) {
                continue;
            }
            String name = cols.get(0).trim();
            if (name.isEmpty()) {
                continue;
            }
            String last = cols.get(cols.size() - 1).trim();
            String path = (last.contains("\\") || last.toLowerCase(Locale.ROOT).endsWith(".sys")) ? last : null;
            list.add(new DriverSnapshot.DriverInfo(name, path, ""));
        }
        return new DriverSnapshot(list);
    }

    @Override
    public ServiceSnapshot snapshotServices() {
        Optional<String> out = OsCommand.output("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-Service | Select-Object Name,DisplayName,@{n='Status';e={$_.Status.ToString()}} | ConvertTo-Json -Compress");
        if (out.isEmpty()) {
            return ServiceSnapshot.empty();
        }
        List<ServiceSnapshot.ServiceInfo> list = new ArrayList<>();
        try {
            Object decoded = Json.decode(out.get().trim());
            List<?> arr = decoded instanceof List<?> l ? l : (decoded == null ? List.of() : List.of(decoded));
            for (Object item : arr) {
                if (item instanceof Map<?, ?> m) {
                    list.add(new ServiceSnapshot.ServiceInfo(str(m.get("Name")), str(m.get("DisplayName")), str(m.get("Status"))));
                }
            }
        } catch (RuntimeException ignored) {
            return ServiceSnapshot.empty();
        }
        return new ServiceSnapshot(list);
    }

    // ------------------------------------------------------------------ 注册表

    @Override
    public List<RegistryHit> scanRegistry(List<RegistryPattern> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<RegistryHit> hits = new ArrayList<>();
        for (RegistryPattern pattern : patterns) {
            if (pattern == null || pattern.key() == null || pattern.key().isBlank()) {
                continue;
            }
            // reg query 只判键是否存在；键不存在时退出码非 0，OsCommand.output 返回 empty
            if (OsCommand.output("reg", "query", pattern.key()).isPresent()) {
                hits.add(new RegistryHit(pattern.key(), pattern.weight()));
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------ 网络

    @Override
    public NetworkSnapshot snapshotNetwork() {
        Optional<String> out = OsCommand.output("netstat", "-ano");
        if (out.isEmpty()) {
            return NetworkSnapshot.empty();
        }
        Map<Integer, String> names = processNamesByPid();
        List<NetworkSnapshot.ConnectionInfo> list = new ArrayList<>();
        for (String line : out.get().split("\\R")) {
            String trimmed = line.trim();
            boolean tcp = trimmed.startsWith("TCP");
            boolean udp = trimmed.startsWith("UDP");
            if (!tcp && !udp) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String protocol = parts[0];
            String local = parts[1];
            String remote = parts[2];
            int pid;
            NetworkSnapshot.ConnectionState state;
            if (udp) {
                pid = toIntOrZero(parts[3]);
                state = NetworkSnapshot.ConnectionState.OTHER;
            } else {
                if (parts.length < 5) {
                    continue;
                }
                state = parseState(parts[3]);
                pid = toIntOrZero(parts[4]);
            }
            list.add(new NetworkSnapshot.ConnectionInfo(
                    protocol,
                    localAddress(local),
                    localPort(local),
                    remoteAddress(remote),
                    remotePort(remote),
                    state,
                    pid,
                    names.getOrDefault(pid, "")));
        }
        return new NetworkSnapshot(list);
    }

    @Override
    public ModuleSnapshot snapshotModules(String processName) {
        if (!client.reachable()) {
            return ModuleSnapshot.empty(processName);
        }
        return client.modules(processName);
    }

    @Override
    public MemoryScanResult scanMemory(String processName, byte[] pattern, byte[] mask) {
        if (!client.reachable()) {
            return MemoryScanResult.unsupported(null, "PaccManager 探针不可达");
        }
        return client.scan(processName, null, pattern, mask);
    }

    // ------------------------------------------------------------------ 签名 / 注入（三层架构 §4）

    @Override
    public List<SignatureResult> verifyModuleSignatures(String processName) {
        if (!client.reachable()) {
            return List.of();
        }
        return client.verifyModules(processName);
    }

    @Override
    public List<SignatureResult> verifyFileSignatures(List<Path> files) {
        if (!client.reachable()) {
            return List.of();
        }
        return client.verifyFiles(files);
    }

    @Override
    public InjectionReport detectInjection(String processName) {
        if (!client.reachable()) {
            return InjectionReport.unsupported("PaccManager 探针不可达");
        }
        return client.detectInjection(processName);
    }

    // ------------------------------------------------------------------ USB

    @Override
    public List<UsbDevice> enumerateUsb() {
        Optional<String> out = OsCommand.output("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_PnPEntity | Where-Object { $_.DeviceID -like 'USB*' } | "
                        + "Select-Object DeviceID,Description,Manufacturer | ConvertTo-Json -Compress");
        if (out.isEmpty()) {
            return List.of();
        }
        List<UsbDevice> list = new ArrayList<>();
        try {
            Object decoded = Json.decode(out.get().trim());
            List<?> arr = decoded instanceof List<?> l ? l : (decoded == null ? List.of() : List.of(decoded));
            for (Object item : arr) {
                if (item instanceof Map<?, ?> m) {
                    String vidPid = extractVidPid(str(m.get("DeviceID")));
                    if (vidPid != null) {
                        list.add(new UsbDevice(vidPid, str(m.get("Description")), str(m.get("Manufacturer"))));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return list;
    }

    // ------------------------------------------------------------------ 内部工具

    private Map<Integer, String> processNamesByPid() {
        Map<Integer, String> map = new HashMap<>();
        for (ProcessSnapshot.ProcessInfo p : snapshotProcesses().processes()) {
            map.putIfAbsent(p.pid(), p.name());
        }
        return map;
    }

    private static String extractVidPid(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        Matcher m = VID_PID.matcher(deviceId);
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
    }

    private static NetworkSnapshot.ConnectionState parseState(String token) {
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "ESTABLISHED" -> NetworkSnapshot.ConnectionState.ESTABLISHED;
            case "LISTENING", "LISTEN" -> NetworkSnapshot.ConnectionState.LISTEN;
            case "TIME_WAIT" -> NetworkSnapshot.ConnectionState.TIME_WAIT;
            case "CLOSE_WAIT" -> NetworkSnapshot.ConnectionState.CLOSE_WAIT;
            case "SYN_SENT" -> NetworkSnapshot.ConnectionState.SYN_SENT;
            default -> NetworkSnapshot.ConnectionState.OTHER;
        };
    }

    private static String localAddress(String endpoint) {
        int i = endpoint.lastIndexOf(':');
        return i < 0 ? endpoint : endpoint.substring(0, i);
    }

    private static int localPort(String endpoint) {
        int i = endpoint.lastIndexOf(':');
        return i < 0 ? 0 : toIntOrZero(endpoint.substring(i + 1));
    }

    private static String remoteAddress(String endpoint) {
        if (endpoint == null || endpoint.startsWith("*") || "0.0.0.0:0".equals(endpoint) || "[::]:0".equals(endpoint)) {
            return null;
        }
        int i = endpoint.lastIndexOf(':');
        return i < 0 ? endpoint : endpoint.substring(0, i);
    }

    private static int remotePort(String endpoint) {
        if (endpoint == null) {
            return 0;
        }
        int i = endpoint.lastIndexOf(':');
        return i < 0 ? 0 : toIntOrZero(endpoint.substring(i + 1));
    }

    private static int toIntOrZero(String s) {
        Integer v = toInt(s);
        return v == null ? 0 : v;
    }

    private static Integer toInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 按 CSV 规则切分一行（字段可能被双引号包裹、内含逗号）。 */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }
}