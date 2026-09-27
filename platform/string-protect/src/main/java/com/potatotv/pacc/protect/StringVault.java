package com.potatotv.pacc.protect;

import java.nio.charset.StandardCharsets;

/**
 * 字符串加固的运行时支持类，与构建期生成器 {@link StringProtectorGenerator} 配对使用。
 *
 * <p>加密方式就是设计文档 3.2.3 给的样例：逐索引键控 XOR，{@code data[i] ^ (key + i)}。
 * 生成器把敏感字面量的 UTF-8 字节加密后写成 Java 字节数组常量，运行时在这里解密回字符串。</p>
 *
 * <p><b>能力边界（不夸大）：</b>逐索引键控对确定性分析者是很弱的——密钥是单个 int，
 * 换明文不换密钥，攻击者只要拿到一份密文 + 对应明文就能反推 {@code key + i} 序列，
 * 从而解出其余所有字面量。它挡住的只是「直接 strings/grep 常量池」这一步，
 * 把逆向成本从「搜一下」抬到「写几行脚本脱壳」，<b>并不提供机密性</b>。
 * 真正的机密性要靠密钥不落盘（KMS/远端下发），不在本工具职责内。</p>
 */
public final class StringVault {

    private StringVault() {
    }

    /** 加密：明文字符串 → 密文字节数组（UTF-8）。 */
    public static byte[] encrypt(String plain, int key) {
        if (plain == null) {
            throw new NullPointerException("plain");
        }
        return crypt(plain.getBytes(StandardCharsets.UTF_8), key);
    }

    /** 解密：密文字节数组 → 明文字符串（UTF-8）。 */
    public static String decrypt(byte[] data, int key) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        return new String(crypt(data, key), StandardCharsets.UTF_8);
    }

    /**
     * 对称变换：{@code out[i] = data[i] ^ (key + i)}。
     * 对同一 key 调用两次即还原原文；对空数组返回空数组。key 为 int，{@code key + i} 按 int 溢出后取低 8 位。
     */
    public static byte[] crypt(byte[] data, int key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ (key + i));
        }
        return out;
    }
}