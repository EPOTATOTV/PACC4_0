package com.potatotv.pacc.util;

import java.util.regex.Pattern;

/**
 * v5.0 插件包签名工具：校验 SHA-256 十六进制摘要格式，防止非法签名入库。
 */
public final class PluginSignature {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private PluginSignature() {
    }

    /** 是否为合法的 UTF-8 小写/大写 SHA-256 十六进制字符串（64 位）。 */
    public static boolean isValidSha256(String hex) {
        return hex != null && SHA256_HEX.matcher(hex).matches();
    }

    /**
     * 计算字节内容的 SHA-256 摘要（十六进制）。
     *
     * @param bytes 插件包内容（可为纯文本/二进制，调用方自行载入）
     * @return 64 位十六进制摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}