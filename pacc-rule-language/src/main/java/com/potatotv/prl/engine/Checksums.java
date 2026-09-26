package com.potatotv.prl.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 校验和（设计文档 §2.13.1 版本表的 {@code checksum char(64)}）。
 *
 * <p>放在一处是因为热加载（内容指纹）和版本库（版本校验）必须算出同一个值：热加载说「文件没变」
 * 而版本库说「checksum 变了」，运维就没法信任任何一个。</p>
 */
final class Checksums {

    private Checksums() {
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里；真走到了也不该让校验静默降级。
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}