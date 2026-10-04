package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.RegistryPattern;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 注册表痕迹检测（文档 §4.5，仅 Windows）：匹配 25+ 项作弊软件注册表键。
 *
 * <p>只判键是否存在，不读取键值内容（文档 §9 注意事项 3）。其中 IFEO 镜像劫持与
 * AppInit_DLLs / AppCertDLLs 全局注入是高危项（权重 45-50），命中即单独计入
 * {@code ext_registry_ifeo_hits}，供 {@code ifeo_hijack} 规则无条件判定。</p>
 *
 * <p>产出扩展特征（{@code ext_registry_}*），供 {@code cheat_registry_trace} /
 * {@code ifeo_hijack} 两条 PRL 规则读取。</p>
 */
public final class RegistryScanner implements Detector {

    private static final String ID = "registry_scanner";
    /** 注册表变化慢，120s 一次（文档 §8.3）。 */
    private static final long INTERVAL_MS = 120_000L;
    /** 判定为高严重的分数线（文档 §4.5）。 */
    private static final int HIGH_SCORE = 50;

    private static final List<RegistryPattern> CHEAT_KEYS = List.of(
            // ---- 内存修改器 ----
            new RegistryPattern("HKCU\\Software\\Cheat Engine", 25),
            new RegistryPattern("HKCU\\Software\\ArtMoney", 20),
            new RegistryPattern("HKCU\\Software\\WeMod", 20),
            // ---- 基岩版作弊客户端 ----
            new RegistryPattern("HKCU\\Software\\Horion", 35),
            new RegistryPattern("HKCU\\Software\\Zephyr", 30),
            new RegistryPattern("HKCU\\Software\\Codebreak", 30),
            new RegistryPattern("HKCU\\Software\\32K Client", 35),
            new RegistryPattern("HKCU\\Software\\Vape", 30),
            new RegistryPattern("HKCU\\Software\\Neverlose", 30),
            // ---- 注入工具 ----
            new RegistryPattern("HKCU\\Software\\Process Hacker", 20),
            new RegistryPattern("HKCU\\Software\\Extreme Injector", 25),
            new RegistryPattern("HKCU\\Software\\Xenos", 20),
            // ---- 调试器 ----
            new RegistryPattern("HKCU\\Software\\Hex-Rays", 15),
            new RegistryPattern("HKCU\\Software\\OLLYDBG", 20),
            new RegistryPattern("HKCU\\Software\\x64dbg", 20),
            // ---- 宏 / 脚本工具 ----
            new RegistryPattern("HKCU\\Software\\AutoHotkey", 15),
            new RegistryPattern("HKCU\\Software\\AutoIt", 15),
            new RegistryPattern("HKCU\\Software\\按键精灵", 20),
            // ---- 反检测工具 ----
            new RegistryPattern("HKCU\\Software\\HxD", 10),
            // ---- 驱动 / 服务 ----
            new RegistryPattern("HKLM\\SYSTEM\\CurrentControlSet\\Services\\dbk64", 40),
            new RegistryPattern("HKLM\\SYSTEM\\CurrentControlSet\\Services\\kprocesshacker", 35),
            new RegistryPattern("HKLM\\SYSTEM\\CurrentControlSet\\Services\\TitanHide", 40),
            // ---- IFEO 镜像劫持（高危） ----
            new RegistryPattern("HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options\\Minecraft.Windows.exe", 50),
            new RegistryPattern("HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options\\javaw.exe", 50),
            new RegistryPattern("HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options\\java.exe", 50),
            // ---- AppCertDLLs 注入 / AppInit_DLLs 全局钩子（高危） ----
            new RegistryPattern("HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\AppCertDLLs", 45),
            new RegistryPattern("HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Windows\\AppInit_DLLs", 45));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        OsInfo os = ctx.osInfo();
        if (!os.isWindows() || !ctx.systemProbe().isSupported(SystemProbe.Capability.REGISTRY)) {
            return Optional.empty();
        }
        List<RegistryHit> hits = ctx.systemProbe().scanRegistry(CHEAT_KEYS);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        int score = 0;
        List<String> ifeo = new ArrayList<>();
        for (RegistryHit hit : hits) {
            score += hit.weight();
            if (isGlobalInjectKey(hit.key())) {
                ifeo.add(hit.key());
            }
        }
        int capped = Math.min(100, score);
        ctx.putExtended("ext_registry_score", capped);
        ctx.putExtended("ext_registry_ifeo_hits", ifeo.size());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("keys", hits.stream().map(RegistryHit::key).toList());
        detail.put("score", capped);
        return Optional.of(new DetectionEvent(
                "cheat_registry_trace",
                score >= HIGH_SCORE ? "high" : "medium",
                capped,
                null, null, null, os.summary(),
                Json.encode(detail)));
    }

    /** IFEO / AppCertDLLs / AppInit_DLLs：全局注入类高危键。 */
    private static boolean isGlobalInjectKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("image file execution options")
                || lower.contains("appcertdlls")
                || lower.contains("appinit_dlls");
    }
}