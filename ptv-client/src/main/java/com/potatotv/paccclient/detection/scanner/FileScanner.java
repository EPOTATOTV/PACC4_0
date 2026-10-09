package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.PathHit;
import com.potatotv.paccclient.probe.PathPattern;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 文件系统痕迹检测（文档 §4.4）：在白名单目录里按路径名匹配 80+ 种作弊软件安装目录 / 作弊 mod 文件。
 *
 * <p>只扫描常见位置（{@code %APPDATA%} / {@code %LOCALAPPDATA%} / 用户 Downloads /
 * {@code .minecraft} / Program Files），不做全盘扫描；匹配只针对<b>路径名</b>，不打开、不读取
 * 文件内容（文档 §9 注意事项 3 隐私边界）。每项按权重累加，命中即报（单条 mod 文件权重 40）。</p>
 *
 * <p>产出扩展特征（{@code ext_file_}*），供 {@code cheat_file_trace} PRL 规则读取。</p>
 */
public final class FileScanner implements Detector {

    private static final String ID = "file_scanner";
    /** 文件变化不频繁，60s 一次（文档 §8.3）。 */
    private static final long INTERVAL_MS = 60_000L;
    /** 判定为高严重的分数线（文档 §4.4）。 */
    private static final int HIGH_SCORE = 60;

    private static final List<PathPattern> CHEAT_PATHS = List.of(
            // ---- 内存修改器 ----
            new PathPattern("*/Cheat Engine/*", 20),
            new PathPattern("*/CheatEngine/*", 20),
            new PathPattern("*/ArtMoney/*", 15),
            new PathPattern("*/WeMod/*", 20),
            new PathPattern("*/FLiNG Trainer/*", 15),
            // ---- 基岩版作弊客户端 ----
            new PathPattern("*/Horion/*", 30),
            new PathPattern("*/AppData/Roaming/Horion/*", 35),
            new PathPattern("*/Zephyr/*", 25),
            new PathPattern("*/Codebreak/*", 25),
            new PathPattern("*/32K Client/*", 30),
            new PathPattern("*/BedrockHax/*", 20),
            new PathPattern("*/Crystal Client/*", 20),
            new PathPattern("*/Vape/*", 30),
            new PathPattern("*/Neverlose/*", 30),
            // ---- Java 版作弊客户端（mod 文件） ----
            new PathPattern("*/.minecraft/mods/wurst*.jar", 40),
            new PathPattern("*/.minecraft/mods/meteor-client*.jar", 40),
            new PathPattern("*/.minecraft/mods/impact*.jar", 40),
            new PathPattern("*/.minecraft/mods/sigma*.jar", 40),
            new PathPattern("*/.minecraft/mods/future*.jar", 40),
            new PathPattern("*/.minecraft/mods/inertia*.jar", 40),
            new PathPattern("*/.minecraft/mods/aristois*.jar", 40),
            new PathPattern("*/.minecraft/mods/liquidbounce*.jar", 40),
            new PathPattern("*/.minecraft/mods/fdp*.jar", 40),
            new PathPattern("*/.minecraft/mods/vape*.jar", 40),
            new PathPattern("*/.minecraft/mods/skillclient*.jar", 40),
            new PathPattern("*/.minecraft/mods/reflex*.jar", 40),
            new PathPattern("*/.minecraft/mods/kami*.jar", 35),
            new PathPattern("*/.minecraft/mods/gamesense*.jar", 35),
            new PathPattern("*/.minecraft/mods/postman*.jar", 35),
            new PathPattern("*/.minecraft/mods/thunderhack*.jar", 35),
            new PathPattern("*/.minecraft/mods/rusherhack*.jar", 35),
            new PathPattern("*/.minecraft/mods/pyro*.jar", 35),
            new PathPattern("*/.minecraft/mods/boze*.jar", 35),
            new PathPattern("*/.minecraft/mods/neverlose*.jar", 40),
            new PathPattern("*/.minecraft/mods/rise*.jar", 35),
            new PathPattern("*/.minecraft/mods/tenacity*.jar", 35),
            new PathPattern("*/.minecraft/mods/huzuni*.jar", 30),
            new PathPattern("*/.minecraft/mods/nodus*.jar", 30),
            new PathPattern("*/.minecraft/mods/nova*.jar", 30),
            new PathPattern("*/.minecraft/mods/osiris*.jar", 30),
            new PathPattern("*/.minecraft/mods/quantum*.jar", 30),
            new PathPattern("*/.minecraft/mods/raven*.jar", 30),
            new PathPattern("*/.minecraft/mods/seppuku*.jar", 30),
            new PathPattern("*/.minecraft/mods/3arthh4ck*.jar", 30),
            // ---- Java 版作弊客户端配置目录 ----
            new PathPattern("*/.minecraft/meteor-client/*", 35),
            new PathPattern("*/.minecraft/impact/*", 35),
            new PathPattern("*/.minecraft/sigma/*", 35),
            new PathPattern("*/.minecraft/future/*", 35),
            new PathPattern("*/.minecraft/inertia/*", 35),
            new PathPattern("*/.minecraft/aristois/*", 35),
            new PathPattern("*/.minecraft/kami/*", 30),
            new PathPattern("*/.minecraft/rusherhack/*", 30),
            // ---- 注入工具 ----
            new PathPattern("*/Extreme Injector/*", 25),
            new PathPattern("*/Process Hacker/*", 20),
            new PathPattern("*/Xenos Injector/*", 20),
            new PathPattern("*/GH Injector/*", 15),
            // ---- 调试器 / 反汇编器 ----
            new PathPattern("*/x64dbg/*", 20),
            new PathPattern("*/OllyDbg/*", 20),
            new PathPattern("*/IDA Pro/*", 15),
            new PathPattern("*/Ghidra/*", 10),
            // ---- 宏 / 脚本工具 ----
            new PathPattern("*/AutoHotkey/*", 15),
            new PathPattern("*.ahk", 10),
            new PathPattern("*/AutoIt3/*", 15),
            new PathPattern("*/按键精灵/*", 20),
            new PathPattern("*/简单百宝箱/*", 20),
            // ---- 反检测工具 ----
            new PathPattern("*/HxD/*", 10),
            new PathPattern("*/ScyllaHide/*", 25),
            new PathPattern("*/TitanHide/*", 30),
            // ---- 移动平台（模拟器共享目录） ----
            new PathPattern("*/八门神器/*", 25),
            new PathPattern("*/烧饼修改器/*", 25),
            new PathPattern("*/葫芦侠/*", 25),
            new PathPattern("*/GameGuardian/*", 25),
            new PathPattern("*/LuckyPatcher/*", 20));

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
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.FILES)) {
            return Optional.empty();
        }
        List<PathHit> hits = ctx.systemProbe().scanPaths(CHEAT_PATHS, scanRoots());
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        int score = 0;
        int modHits = 0;
        for (PathHit hit : hits) {
            score += hit.weight();
            if (isModPath(hit.path())) {
                modHits++;
            }
        }
        int capped = Math.min(100, score);
        ctx.putExtended("ext_file_score", capped);
        ctx.putExtended("ext_file_trace_hits", hits.size());
        ctx.putExtended("ext_file_mod_hits", modHits);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hits", hits.stream().map(PathHit::path).toList());
        detail.put("score", capped);
        return Optional.of(new DetectionEvent(
                "cheat_file_trace",
                score >= HIGH_SCORE ? "high" : "medium",
                capped,
                hits.get(0).path(), null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /** 常见扫描根目录；环境变量缺失的平台（Linux/macOS）自然得到空列表。 */
    static List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        addEnv(roots, "APPDATA");
        addEnv(roots, "LOCALAPPDATA");
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            roots.add(Paths.get(userProfile, "Downloads"));
            roots.add(Paths.get(userProfile, ".minecraft"));
        }
        addEnv(roots, "ProgramFiles");
        return roots;
    }

    private static void addEnv(List<Path> roots, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            roots.add(Paths.get(value));
        }
    }

    /** 是否为 {@code .minecraft/mods} 下的作弊 mod 文件。 */
    private static boolean isModPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/.minecraft/mods/");
    }
}