package com.potatotv.pcu;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 工具：校验和出现在服务端清单里，可能带 {@code sha256:} 前缀，这里统一处理。
 */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] digest(byte[] data) {
        return newDigest().digest(data);
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }

    public static String hexOfFile(java.nio.file.Path file) {
        MessageDigest md = newDigest();
        byte[] buf = new byte[64 * 1024];
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            throw new PcuException("计算文件 SHA-256 失败：" + file, e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 去掉 {@code sha256:} / {@code SHA256:} 前缀并转小写。 */
    public static String normalize(String checksum) {
        if (checksum == null) {
            return null;
        }
        String s = checksum.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) {
            String algo = s.substring(0, colon).trim();
            if (!"sha256".equalsIgnoreCase(algo)) {
                throw new PcuException("不支持的校验和算法：" + algo);
            }
            s = s.substring(colon + 1).trim();
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** 期望值与实际值是否一致（大小写不敏感，允许带算法前缀）。 */
    public static boolean matches(String expected, String actualHex) {
        String e = normalize(expected);
        return e != null && e.equals(normalize(actualHex));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256，缺失说明运行环境已被破坏
            throw new PcuException("运行环境缺少 SHA-256 实现", e);
        }
    }
}