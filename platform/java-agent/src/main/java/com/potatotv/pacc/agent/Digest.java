package com.potatotv.pacc.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具：统一使用 SHA-256（十六进制小写）。纯 JDK 实现，供 mod 签名校验与字节码完整性比对复用。
 */
final class Digest {

    private Digest() {
    }

    /** 新建 SHA-256 摘要器（调用方需自行处理算法缺失，JDK 必然支持）。 */
    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 计算字节数组摘要；入参为 null 时返回 null。 */
    static String sha256(byte[] data) {
        if (data == null) return null;
        return hex(sha256Digest().digest(data));
    }

    /** 计算文件摘要（流式，不整文件载入内存）；读取失败返回 null。 */
    static String sha256File(Path path) {
        if (path == null) return null;
        try (InputStream in = Files.newInputStream(path)) {
            return sha256(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** 计算流内容摘要。 */
    static String sha256(InputStream in) throws IOException {
        MessageDigest md = sha256Digest();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** 字节数组转十六进制小写字符串。 */
    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}