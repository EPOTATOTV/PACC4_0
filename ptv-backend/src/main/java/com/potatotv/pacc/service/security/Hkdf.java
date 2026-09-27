package com.potatotv.pacc.service.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HKDF（基于 HMAC-SHA256 的密钥派生），实现 RFC 5869 的 extract + expand 两段式。
 *
 * <p>为什么自己实现：子密钥必须由「永不出服务器的根密钥」确定性派生，且需要一个可随用途隔离的
 * 派生域（info）。JDK 未提供 HKDF，而本子系统不允许引入新依赖，故按 RFC 5869 直接用
 * {@link Mac}（HmacSHA256）实现——逻辑短（约 20 行）且易于对照规范复核。</p>
 *
 * <p>仅覆盖 SHA-256 场景（HashLen = 32 字节）：{@code extract(salt, ikm) = HMAC(salt, ikm)}；
 * {@code expand} 按 {@code T(i) = HMAC(PRK, T(i-1) | info | i)} 迭代，i 为 1 起的单字节计数器。
 * 输出长度上限为 255*32 字节（RFC 5869 规定）。</p>
 *
 * <p>本类为无状态纯函数工具，不提供 CLI，不持有任何密钥。</p>
 */
public final class Hkdf {

    /** SHA-256 输出长度（字节），即 RFC 5869 的 HashLen。 */
    private static final int HASH_LEN = 32;
    /** RFC 5869 允许的最大输出字节数。 */
    private static final int MAX_OUTPUT = 255 * HASH_LEN;
    private static final String HMAC_ALG = "HmacSHA256";

    private Hkdf() {
    }

    /**
     * HKDF-Extract：以 salt 为 HMAC 密钥、ikm 为消息，得到伪随机密钥 PRK。
     * <p>按 RFC 5869，salt 缺省时应使用 HashLen 个零字节，此处保持一致。</p>
     *
     * @param salt 派生盐，可为 null/空（将退化为 32 字节全零）
     * @param ikm  输入密钥材料（本系统即根密钥字节）
     * @return 32 字节 PRK
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm == null ? new byte[0] : ikm);
    }

    /**
     * HKDF-Expand：把 PRK 扩展为指定长度的输出密钥。
     *
     * @param prk    由 {@link #extract} 得到的 32 字节伪随机密钥
     * @param info   派生域（用途上下文），可为 null
     * @param length 期望输出字节数
     * @return 长度为 {@code length} 的输出密钥
     * @throws IllegalArgumentException length 为负或超过 255*32
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("HKDF 输出长度不能为负");
        }
        if (length > MAX_OUTPUT) {
            throw new IllegalArgumentException("HKDF 输出长度超过上限 " + MAX_OUTPUT + " 字节");
        }
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        int counter = 1;
        while (pos < length) {
            Mac mac = newMac(prk);
            mac.update(t);
            if (info != null && info.length > 0) {
                mac.update(info);
            }
            mac.update((byte) counter);
            t = mac.doFinal();
            int copy = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, copy);
            pos += copy;
            counter++;
        }
        return out;
    }

    /**
     * 一步派生：{@code expand(extract(salt, root), info, length)}。
     *
     * @param root   根密钥字节（永不出服务器）
     * @param salt   派生盐字节（库中存其十六进制）
     * @param info   派生域字符串（用途），UTF-8 编码后参与 expand
     * @param length 输出字节数
     */
    public static byte[] derive(byte[] root, byte[] salt, String info, int length) {
        byte[] prk = extract(salt, root);
        byte[] infoBytes = info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8);
        return expand(prk, infoBytes, length);
    }

    /** 字节数组转小写十六进制字符串。 */
    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 字符串（UTF-8）的 SHA-256 十六进制摘要，用于指纹与链哈希。 */
    public static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Mac newMac(byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 初始化失败", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        return newMac(key).doFinal(data);
    }
}