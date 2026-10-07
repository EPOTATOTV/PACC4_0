package com.potatotv.paccclient.detection.input;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * 宏配置文件痕迹扫描（文档 §3.6）。
 *
 * <p>在 {@code %APPDATA% / %LOCALAPPDATA% / USERPROFILE / Documents} 下有界遍历
 * （深度 ≤5、条目 ≤20000），<b>只看路径与最后修改时间，不读文件内容</b>，命中已知宏软件的
 * 配置目录 / 扩展名且最近 7 天改动的才计入。默认路径在测试或非 Windows 环境可能不存在，
 * {@link #scan()} 遇到不可读目录安静跳过、返回空表。</p>
 */
public final class MacroFileTracer {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_ENTRIES = 20000;
    private static final int RECENT_DAYS = 7;

    private final List<Path> roots;

    public MacroFileTracer() {
        this(defaultRoots());
    }

    /** 测试接缝：注入扫描根，避免碰真实目录。 */
    MacroFileTracer(List<Path> roots) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
    }

    /**
     * 一条宏文件命中。
     *
     * @param software 所属宏软件标识
     * @param path     文件路径
     * @param daysAgo  距上次修改的天数
     * @param weight   该痕迹的权重
     */
    public record MacroFileHit(String software, String path, long daysAgo, int weight) {
    }

    /** 扫描全部根，返回最近 7 天修改的宏配置命中。 */
    public List<MacroFileHit> scan() {
        List<MacroFileHit> hits = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            walk(root, hits, now);
        }
        return hits;
    }

    /** 最近 7 天修改的宏文件数（便捷方法）。 */
    public int recentCount() {
        return scan().size();
    }

    private static void walk(Path root, List<MacroFileHit> hits, long now) {
        int[] counter = {0};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++counter[0] > MAX_ENTRIES) {
                        return FileVisitResult.TERMINATE;
                    }
                    MacroMatch match = classify(file.toString());
                    if (match == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    long daysAgo = (now - attrs.lastModifiedTime().toMillis()) / 86_400_000L;
                    if (daysAgo >= 0 && daysAgo <= RECENT_DAYS) {
                        hits.add(new MacroFileHit(match.software(), file.toString(), daysAgo, match.weight()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return counter[0] > MAX_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            // 目录不可读 / 被占用：安静跳过整个根
        }
    }

    private record MacroMatch(String software, int weight) {
    }

    /** 路径分类：命中返回宏软件标识与权重，否则 null。 */
    private static MacroMatch classify(String path) {
        String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.contains("/lghub/") && (p.endsWith(".xml") || p.endsWith(".json"))) {
            return new MacroMatch("logitech_ghub", 15);
        }
        if (p.contains("/razer/synapse/")) {
            return new MacroMatch("razer_synapse", 15);
        }
        if (p.contains("/steelseries/engine/")) {
            return new MacroMatch("steelseries_engine", 15);
        }
        if (p.contains("/corsair/icue/")) {
            return new MacroMatch("corsair_icue", 15);
        }
        if (p.endsWith(".ahk")) {
            return new MacroMatch("autohotkey", 20);
        }
        if (p.contains("/macros/") && p.endsWith(".xml")) {
            return new MacroMatch("generic_macro_xml", 10);
        }
        if (p.contains("/profiles/") && p.endsWith(".macro")) {
            return new MacroMatch("generic_macro_profile", 15);
        }
        return null;
    }

    private static List<Path> defaultRoots() {
        List<Path> list = new ArrayList<>();
        add(list, System.getenv("APPDATA"));
        add(list, System.getenv("LOCALAPPDATA"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            add(list, home + System.getProperty("file.separator") + "Documents");
            add(list, home);
        }
        return list;
    }

    private static void add(List<Path> list, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(raw);
            if (!list.contains(p)) {
                list.add(p);
            }
        } catch (RuntimeException e) {
            // 非法路径直接用不到
        }
    }
}