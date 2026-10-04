package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.GlobMatcher;
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
 * 进程级检测（文档 §4.1）：扫描 80+ 种已知作弊 / 辅助软件进程名与窗口标题。
 *
 * <p>分类加权：作弊客户端 / 注入器 / 反检测工具 35、内存修改器 / 移动作弊 30、调试器 20、
 * 宏与网络工具 10；窗口标题命中额外 +15。单条进程名满足不了 30 分阈值的类别（调试器 / 宏工具）
 * 不会单独触发，必须叠加其它指标 —— 这是文档 §9 注意事项 1 多指标加权原则的落地。</p>
 *
 * <p>产出扩展特征（{@code ext_process_}*，文档 §7.1），供 {@code cheat_process} /
 * {@code suspicious_window} 两条 PRL 规则读取。</p>
 */
public final class ProcessScanner implements Detector {

    private static final String ID = "process_scanner";
    private static final long INTERVAL_MS = 5_000L;
    /** 窗口标题命中权重（文档 §4.1）。 */
    private static final int WINDOW_WEIGHT = 15;
    /** 触发阈值（文档 §4.1）。 */
    private static final int THRESHOLD = 30;

    /** 按类别给严重度权重：客户端/注入器/反检测 > 内存修改器 > 调试器 > 宏工具。 */
    private record Category(String name, int weight, Set<String> patterns) {
    }

    private static final List<Category> CATEGORIES = List.of(
            new Category("bedrock_client", 35, Set.of(
                    "horion.exe", "horion-*.exe", "zephyr.exe", "codebreak.exe",
                    "32k.exe", "32kclient.exe", "bedrockhax.exe", "crystalclient.exe",
                    "nbtclient.exe", "fdpclient.exe", "liquidbounce.exe",
                    "skillclient.exe", "vape.exe", "reflex.exe", "wurst.exe",
                    "meteor.exe", "rusherhack.exe", "pyro.exe", "boze.exe",
                    "neverlose.exe")),
            new Category("java_client", 35, Set.of(
                    "wurst-installer.exe", "meteor-client-installer.exe",
                    "impact-installer.exe", "sigma-installer.exe",
                    "future-installer.exe", "inertia-installer.exe",
                    "aristois-installer.exe", "liquidbounce-installer.exe",
                    "fdp-installer.exe", "vape-launcher.exe",
                    "skillclient-launcher.exe", "reflex-launcher.exe",
                    "kamiblue-launcher.exe", "gamesense-launcher.exe",
                    "postman-launcher.exe", "thunderhack-launcher.exe",
                    "rusherhack-launcher.exe", "pyro-launcher.exe",
                    "boze-launcher.exe", "neverlose-launcher.exe",
                    "rise-launcher.exe", "tenacity-launcher.exe")),
            new Category("injector", 35, Set.of(
                    "processhacker.exe", "extremeinjector.exe", "xenos.exe",
                    "injector.exe", "dllinjector.exe", "ghinjector.exe",
                    "injectorgadget.exe", "sazinjector.exe", "blackdove.exe")),
            new Category("anti_detect", 35, Set.of(
                    "hxd.exe", "rehex.exe", "cffexplorer.exe",
                    "scylla.exe", "titanhide.exe", "vmprotect.exe", "themida.exe")),
            new Category("memory_editor", 30, Set.of(
                    "cheatengine.exe", "cheatengine-x86_64.exe", "artmoney.exe",
                    "cheathappens.exe", "flingtrainer.exe", "wemod.exe",
                    "mhs.exe", "tsearch.exe", "gamegain.exe", "gameboost.exe")),
            new Category("mobile_cheat", 30, Set.of(
                    "bamenshenqi.exe", "shaobingxiugaiqi.exe", "huluxia.exe",
                    "gameguardian.exe", "luckypatcher.exe")),
            new Category("debugger", 20, Set.of(
                    "x64dbg.exe", "x32dbg.exe", "ollydbg.exe", "ida64.exe",
                    "ida.exe", "windbg.exe", "gdb.exe", "lldb.exe",
                    "strace.exe", "ghidrarun.exe", "frida.exe", "frida-server.exe")),
            new Category("network_tool", 10, Set.of(
                    "wireshark.exe", "fiddler.exe", "charles.exe",
                    "mitmproxy.exe", "nmap.exe", "tcpdump.exe", "windump.exe")),
            new Category("macro_tool", 10, Set.of(
                    "autohotkey.exe", "ahk.exe", "autoit3.exe",
                    "luamacros.exe", "macrorecorder.exe", "pulover.exe",
                    "tinytask.exe", "phraseexpress.exe", "robotask.exe",
                    "qmacro.exe", "simplebox.exe", "lghub.exe", "lcore.exe",
                    "razersynapse.exe", "rzsynapse.exe", "steelseriesengine.exe",
                    "sse.exe", "corsaircue.exe", "icue.exe")));

    private static final Set<String> CHEAT_WINDOW_TITLES = Set.of(
            "*Cheat Engine*", "*Horion*", "*Zephyr*", "*Codebreak*",
            "*32K*", "*x64dbg*", "*x32dbg*", "*OllyDbg*", "*IDA*",
            "*Process Hacker*", "*AutoHotkey*", "*ArtMoney*",
            "*Extreme Injector*", "*WeMod*", "*FLiNG*",
            "*Wurst*", "*Meteor*", "*LiquidBounce*", "*Vape*",
            "*按键精灵*", "*简单百宝箱*", "*八门神器*", "*烧饼*");

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
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.PROCESSES)) {
            return Optional.empty();
        }
        ProcessSnapshot snapshot = ctx.systemProbe().snapshotProcesses();
        List<String> matched = new ArrayList<>();
        int score = 0;
        int processHits = 0;
        int clientHits = 0;
        int injectorHits = 0;
        int debuggerHits = 0;
        int windowHits = 0;

        for (ProcessSnapshot.ProcessInfo process : snapshot.processes()) {
            String name = process.name() == null ? "" : process.name().toLowerCase(Locale.ROOT);
            Category category = name.isEmpty() ? null : classify(name);
            if (category != null) {
                matched.add(category.name() + ":" + process.name());
                score += category.weight();
                processHits++;
                switch (category.name()) {
                    case "bedrock_client", "java_client" -> clientHits++;
                    case "injector" -> injectorHits++;
                    case "debugger" -> debuggerHits++;
                    default -> {
                    }
                }
            }
            String title = process.windowTitle();
            if (title != null && !title.isBlank()) {
                for (String pattern : CHEAT_WINDOW_TITLES) {
                    if (GlobMatcher.matches(pattern, title)) {
                        matched.add("window:" + title);
                        score += WINDOW_WEIGHT;
                        windowHits++;
                        break;
                    }
                }
            }
        }

        ctx.putExtended("ext_process_score", Math.min(100, score));
        ctx.putExtended("ext_process_hits", processHits);
        ctx.putExtended("ext_process_client_hits", clientHits);
        ctx.putExtended("ext_process_injector_hits", injectorHits);
        ctx.putExtended("ext_process_debugger_hits", debuggerHits);
        ctx.putExtended("ext_process_window_hits", windowHits);

        if (score < THRESHOLD) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("score", Math.min(100, score));
        detail.put("matched", matched);
        detail.put("categories", categories(matched));
        return Optional.of(new DetectionEvent(
                "cheat_process",
                score >= 70 ? "high" : "medium",
                Math.min(100, score),
                String.join(",", matched),
                null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    private static Category classify(String lowerName) {
        for (Category category : CATEGORIES) {
            for (String pattern : category.patterns()) {
                if (GlobMatcher.matches(pattern, lowerName)) {
                    return category;
                }
            }
        }
        return null;
    }

    private static List<String> categories(List<String> matched) {
        List<String> out = new ArrayList<>();
        for (String m : matched) {
            int i = m.indexOf(':');
            if (i > 0) {
                String c = m.substring(0, i);
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}