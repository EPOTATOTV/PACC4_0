package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.SignatureResult;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 未签名可执行文件扫描（三层架构 §4.5）：在用户可写目录里挑出最近改动过的
 * {@code *.exe / *.dll}，交数字签名验证，统计未签名与黑名单发布者可执行文件数。
 *
 * <p>只做<b>有界遍历</b>：扫描根为 {@code %LOCALAPPDATA% / %APPDATA% / %TEMP% /
 * %USERPROFILE%\Downloads}，深度 ≤ {@value #MAX_DEPTH} 层、每个根最多看
 * {@value #MAX_ENTRIES_PER_ROOT} 个目录项、最多取 {@value #MAX_FILES} 个候选文件交给
 * {@link SystemProbe#verifyFileSignatures(List)}。只按文件名后缀筛选，<b>不读文件内容</b>
 * （文档 §9 注意事项 3 隐私边界）。</p>
 *
 * <p>能力 {@link SystemProbe.Capability#SIGNATURE_VERIFY} 不支持、或没有候选文件时直接返回空
 * 且不写特征。产出扩展特征（{@code ext_sys_}*），供 {@code unsigned_executable} PRL 规则读取。</p>
 */
public final class UnsignedExecutableScanner implements Detector {

    private static final String ID = "unsigned_executable_scanner";
    /** 文档 §4.5：每分钟一次。 */
    private static final long INTERVAL_MS = 60_000L;

    /** 有界遍历参数。 */
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ENTRIES_PER_ROOT = 3000;
    private static final int MAX_FILES = 40;

    /** 判定分数线（文档 §4.5）。 */
    private static final int HIGH_SCORE = 60;
    /** 未签名文件数达到该值判定异常。 */
    private static final int UNSIGNED_THRESHOLD = 5;
    private static final int BLACKLIST_WEIGHT = 40;
    private static final int UNSIGNED_WEIGHT = 5;
    private static final int UNSIGNED_CAP = 5;

    /** 测试注入的扫描根；为 null 时回退到 {@link #scanRoots()}。 */
    private final List<Path> rootsOverride;

    public UnsignedExecutableScanner() {
        this(null);
    }

    /** 允许测试注入扫描根，避免真扫用户目录。包级可见。 */
    UnsignedExecutableScanner(List<Path> rootsOverride) {
        this.rootsOverride = rootsOverride;
    }

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
        SystemProbe probe = ctx.systemProbe();
        if (!probe.isSupported(SystemProbe.Capability.SIGNATURE_VERIFY)) {
            return Optional.empty();
        }

        List<Path> roots = rootsOverride != null ? rootsOverride : scanRoots();
        List<Path> candidates = candidateFiles(roots.toArray(new Path[0]));
        if (candidates.isEmpty()) {
            // 没有候选文件：不验证、不写特征
            return Optional.empty();
        }

        List<SignatureResult> results = probe.verifyFileSignatures(candidates);
        int unsigned = 0;
        List<String> blacklistedFiles = new ArrayList<>();
        String firstUnsigned = null;
        for (SignatureResult result : results) {
            if (result.valid()) {
                continue;
            }
            unsigned++;
            if (firstUnsigned == null) {
                firstUnsigned = result.path();
            }
            if (SignatureBlacklist.isBlacklisted(result.publisher())) {
                blacklistedFiles.add(result.path());
            }
        }
        int blacklisted = blacklistedFiles.size();

        int score = Math.min(100, blacklisted * BLACKLIST_WEIGHT
                + Math.min(unsigned, UNSIGNED_CAP) * UNSIGNED_WEIGHT);
        ctx.putExtended("ext_sys_unsigned_exe_count", unsigned);
        ctx.putExtended("ext_sys_blacklisted_exe_count", blacklisted);
        DllSignatureScanner.mergeScore(ctx, score);

        if (blacklisted == 0 && unsigned < UNSIGNED_THRESHOLD) {
            return Optional.empty();
        }

        String evidencePath = blacklistedFiles.isEmpty() ? firstUnsigned : blacklistedFiles.get(0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("unsigned_files", unsigned);
        detail.put("blacklisted_files", blacklistedFiles);
        return Optional.of(new DetectionEvent(
                "unsigned_executable",
                score >= HIGH_SCORE ? "high" : "medium",
                score,
                evidencePath, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /** 常见扫描根目录；环境变量缺失（非 Windows）时自然得到空列表。 */
    static List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        addEnv(roots, "LOCALAPPDATA");
        addEnv(roots, "APPDATA");
        addEnv(roots, "TEMP");
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            roots.add(Paths.get(userProfile, "Downloads"));
        }
        return roots;
    }

    private static void addEnv(List<Path> roots, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            roots.add(Paths.get(value));
        }
    }

    /**
     * 有界遍历给定的根，筛出 {@code *.exe / *.dll} 候选文件（包级可见，便于单独测试筛选逻辑）。
     *
     * <p>文件按最后修改时间倒序排列，最多返回 {@value #MAX_FILES} 个。</p>
     */
    static List<Path> candidateFiles(Path... roots) {
        List<Path> out = new ArrayList<>();
        if (roots == null) {
            return out;
        }
        for (Path root : roots) {
            if (root != null) {
                collect(root, 0, new int[]{0}, out);
            }
        }
        out.sort(Comparator.comparingLong(UnsignedExecutableScanner::lastModifiedMillis).reversed());
        return out.size() > MAX_FILES ? new ArrayList<>(out.subList(0, MAX_FILES)) : out;
    }

    private static void collect(Path dir, int depth, int[] visited, List<Path> out) {
        if (depth > MAX_DEPTH || visited[0] >= MAX_ENTRIES_PER_ROOT) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (visited[0] >= MAX_ENTRIES_PER_ROOT) {
                    return;
                }
                visited[0]++;
                if (Files.isDirectory(entry)) {
                    collect(entry, depth + 1, visited, out);
                } else if (isCandidate(entry)) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            // 目录不可读时跳过该根，不中断整轮扫描
        }
    }

    private static boolean isCandidate(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".dll");
    }

    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}