package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模块注入检测（文档 §4.2）：对 Minecraft 进程已加载模块做白名单比对。
 *
 * <p>模块枚举本身依赖平台能力（Windows 走 PaccManager 原生探针），{@link SystemProbe#isSupported}
 * 报 false 时直接返回空（文档 §9 注意事项 2 平台能力降级）。判定分两级：</p>
 * <ul>
 *   <li>模块名命中已知作弊模块名单 → {@code known_cheat_module}，critical；</li>
 *   <li>白名单外的未知模块 ≥ 3 个 → {@code suspicious_module}，medium。</li>
 * </ul>
 *
 * <p>产出扩展特征（{@code ext_module_}*），供 {@code known_cheat_module} /
 * {@code suspicious_module} 两条 PRL 规则读取。</p>
 */
public final class ModuleScanner implements Detector {

    private static final String ID = "module_scanner";
    /** 模块枚举成本高于进程，30s 一次（文档 §8.3 给的是 10s，这里取模块变化不频繁的保守值）。 */
    private static final long INTERVAL_MS = 10_000L;

    /** 被扫描的目标进程（基岩版）。 */
    private static final String BEDROCK_PROCESS = "Minecraft.Windows.exe";
    /** 白名单外未知模块数达到该值判定可疑（文档 §4.2）。 */
    private static final int UNKNOWN_THRESHOLD = 3;
    /** 命中已知作弊模块时的固定分。 */
    private static final int CHEAT_SCORE = 95;

    /** Minecraft 基岩版合法模块白名单（随版本维护，文档 §4.2 的节选）。 */
    private static final Set<String> BEDROCK_WHITELIST = Set.of(
            "minecraft.windows.exe", "ntdll.dll", "kernel32.dll", "kernelbase.dll",
            "user32.dll", "gdi32.dll", "advapi32.dll", "shell32.dll", "ole32.dll",
            "d3d11.dll", "dxgi.dll", "d3dcompiler_47.dll", "xinput1_4.dll",
            "xaudio2_9.dll", "windows.storage.dll", "coreuicomponents.dll",
            "ucrtbase.dll", "msvcp140.dll", "vcruntime140.dll",
            "uxtheme.dll", "dwmapi.dll", "winmm.dll", "ws2_32.dll",
            "iphlpapi.dll", "winhttp.dll", "crypt32.dll", "bcrypt.dll");

    /** 已知作弊 DLL / 模块签名（文档 §4.2）。 */
    private static final Set<String> CHEAT_MODULES = Set.of(
            "horion.dll", "zephyr.dll", "codebreak.dll",
            "32k.dll", "bedrockhax.dll", "crystal.dll",
            "nbt.dll", "fdp.dll", "liquidbounce.dll",
            "skillclient.dll", "vape.dll", "reflex.dll",
            "wurst.dll", "meteor.dll", "rusherhack.dll",
            "pyro.dll", "boze.dll", "neverlose.dll",
            "cheatengine-x86_64.exe",
            "speedhack.dll", "xinput9_1_0.dll",
            "frida-agent.dll", "frida-agent-32.dll",
            "dbk64.sys", "kprocesshacker.sys",
            "hack.dll", "cheat.dll", "aimbot.dll",
            "triggerbot.dll", "esp.dll", "wallhack.dll");

    /** 系统模块回退白名单：路径在 Windows 目录下的一律视为系统模块。 */
    private static final Set<String> SYSTEM_MODULES = Set.of(
            "ntdll.dll", "kernel32.dll", "kernelbase.dll", "user32.dll", "gdi32.dll",
            "advapi32.dll", "shell32.dll", "ole32.dll", "ucrtbase.dll",
            "msvcp140.dll", "vcruntime140.dll", "winmm.dll", "ws2_32.dll");

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
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.MODULES)) {
            return Optional.empty();
        }
        if (findProcess(ctx, BEDROCK_PROCESS) == null) {
            // 目标进程未运行：无模块可查
            return Optional.empty();
        }
        ModuleSnapshot snapshot = ctx.systemProbe().snapshotModules(BEDROCK_PROCESS);
        List<ModuleSnapshot.ModuleInfo> modules = snapshot.modules();
        if (modules.isEmpty()) {
            return Optional.empty();
        }

        List<String> cheat = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int whitelisted = 0;
        for (ModuleSnapshot.ModuleInfo module : modules) {
            String name = module.name() == null ? "" : module.name().toLowerCase(Locale.ROOT);
            if (CHEAT_MODULES.contains(name)) {
                cheat.add(name);
            } else if (BEDROCK_WHITELIST.contains(name) || isSystemModule(name, module.path())) {
                whitelisted++;
            } else {
                unknown.add(name);
            }
        }

        int score = cheat.isEmpty() ? (unknown.size() >= UNKNOWN_THRESHOLD ? Math.min(100, 40 + unknown.size() * 5) : 0)
                : CHEAT_SCORE;
        ctx.putExtended("ext_module_score", score);
        ctx.putExtended("ext_module_known_cheat_hits", cheat.size());
        ctx.putExtended("ext_module_unknown_count", unknown.size());
        ctx.putExtended("ext_module_whitelist_ratio", (double) whitelisted / modules.size());

        if (!cheat.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("modules", cheat);
            return Optional.of(new DetectionEvent(
                    "known_cheat_module", "critical", CHEAT_SCORE,
                    String.join(",", cheat), null, null, ctx.osInfo().summary(),
                    Json.encode(detail)));
        }
        if (unknown.size() >= UNKNOWN_THRESHOLD) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unknown_modules", unknown);
            return Optional.of(new DetectionEvent(
                    "suspicious_module", "medium", score,
                    String.join(",", unknown), null, null, ctx.osInfo().summary(),
                    Json.encode(detail)));
        }
        return Optional.empty();
    }

    private static ProcessSnapshot.ProcessInfo findProcess(DetectContext ctx, String name) {
        for (ProcessSnapshot.ProcessInfo p : ctx.systemProbe().snapshotProcesses().processes()) {
            if (p.name() != null && p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isSystemModule(String name, String path) {
        if (SYSTEM_MODULES.contains(name)) {
            return true;
        }
        return path != null && path.toLowerCase(Locale.ROOT).contains("\\windows\\");
    }
}