package com.potatotv.paccclient.store;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 本机指纹（配置敏感值加密的派生口令）：与 C# 侧 ConfigCrypt.MachineFingerprint 字节级一致。
 * <p>规范化形式：raw = "pacc1|os|arch|user|norm(home)|norm(host)"，norm = 去尾反斜杠 + 统一小写；
 * 对 raw 取 sha256 十六进制小写。跨 C# / Java 采用同一输入即得同一指纹，保证互解。</p>
 */
public final class MachineFingerprint {

    private MachineFingerprint() {
    }

    public static String hash() {
        return hashCore("windows", mapArch(System.getProperty("os.arch", "unknown")),
                System.getProperty("user.name", "?"),
                System.getProperty("user.home", "?"),
                hostname());
    }

    /** 将 JVM os.arch 映射到与 C# 一致的架构 token。 */
    private static String mapArch(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "amd64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch;
        };
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 与 C# MachineFingerprintCore 对齐的规范化指纹（注入式，供跨语言锁定测试）。 */
    static String hashCore(String os, String arch, String user, String home, String host) {
        String raw = "pacc1|" + os + "|" + arch + "|" + user
                + "|" + norm(home) + "|" + norm(host);
        return sha256Hex(raw);
    }

    private static String norm(String s) {
        return s.replace('/', '\\')
                .replaceFirst("\\\\+$", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}