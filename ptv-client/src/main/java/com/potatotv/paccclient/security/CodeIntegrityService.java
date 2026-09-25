package com.potatotv.paccclient.security;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码完整性与运行时状态采集。
 *
 * <p>用途有二：其一，为远程证明提供 {@code code_hash}（发行 jar 的 SHA-256）与 {@code runtime_state}；
 * 其二，服务端若下发了期望哈希，可在本地比对判断产物是否被替换。</p>
 *
 * <p>开发态（从 {@code target/classes} 目录运行）没有 jar 可哈希，此时 {@link #jarHash()} 返回空串，
 * 并且 {@link #verify(String)} 一律判定为「通过」——不能因为拿不到哈希就误报完整性异常，否则本地
 * 调试会被自己的防护拦住。</p>
 */
public final class CodeIntegrityService {

    /** 一次完整性核对结论。 */
    public record Assessment(boolean match, String actualHash, String expectedHash) {
    }

    /**
     * 发行 jar 的 SHA-256 十六进制；代码来源不是文件（开发态 classes 目录 / 非常规加载器）时返回空串。
     */
    public String jarHash() {
        try {
            URL location = codeSourceLocation();
            if (location == null) return "";
            Path path = Path.of(location.toURI());
            if (!Files.isRegularFile(path)) return "";
            try (InputStream in = Files.newInputStream(path)) {
                return sha256Hex(in);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 配置文件字节的 SHA-256 十六进制；不存在或不可读时返回空串。 */
    public String resolveConfigHash(String configPath) {
        if (configPath == null || configPath.isBlank()) return "";
        try {
            Path path = Path.of(configPath);
            if (!Files.isRegularFile(path)) return "";
            try (InputStream in = Files.newInputStream(path)) {
                return sha256Hex(in);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 非敏感运行时事实，作为远程证明响应里的 {@code runtime_state}。任一项取不到就跳过。 */
    public Map<String, String> runtimeState() {
        Map<String, String> state = new LinkedHashMap<>();
        try {
            state.put("jvm_version", System.getProperty("java.version", ""));
            state.put("os_name", System.getProperty("os.name", ""));
            state.put("os_arch", System.getProperty("os.arch", ""));
            state.put("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
            state.put("uptime_sec", Long.toString(ManagementFactory.getRuntimeMXBean().getUptime() / 1000));
            state.put("thread_count", Integer.toString(ManagementFactory.getThreadMXBean().getThreadCount()));
            state.put("class_count",
                    Integer.toString(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));
            Runtime rt = Runtime.getRuntime();
            state.put("free_heap_mb", Long.toString((rt.totalMemory() - rt.freeMemory()) / 1048576));
        } catch (RuntimeException e) {
            // 部分字段缺失不影响证明流程
        }
        return state;
    }

    /**
     * 与期望 jar 哈希比对。{@code expectedJarHash} 为空表示未开启固定哈希（pinning），
     * 此时返回 {@code match=true} 并注明未固定；本地无法计算实际哈希（开发态）时同样不误报。
     */
    public Assessment verify(String expectedJarHash) {
        String actual = jarHash();
        if (expectedJarHash == null || expectedJarHash.isBlank()) {
            return new Assessment(true, actual, "");
        }
        String expected = expectedJarHash.trim();
        if (actual.isEmpty()) {
            // 开发态/非常规加载器：没有可比对的基准，按「未固定」处理而非误报
            return new Assessment(true, "", expected);
        }
        return new Assessment(actual.equalsIgnoreCase(expected), actual, expected);
    }

    /** 本类所在代码来源位置。 */
    private static URL codeSourceLocation() {
        ProtectionDomain domain = CodeIntegrityService.class.getProtectionDomain();
        CodeSource source = domain == null ? null : domain.getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /** 流式 SHA-256，避免把整个 jar（数十 MB）读进内存。 */
    private static String sha256Hex(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 便于其他组件对任意字节做同口径哈希（如客户端配置的「显著值」拼接串）。 */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}