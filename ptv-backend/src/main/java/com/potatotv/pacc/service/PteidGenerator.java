package com.potatotv.pacc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 12 位 PTEID 生成器。
 * <pre>
 *  前 2 位固定前缀 "PT"
 *  第 3-4 位为时间戳编码
 *  后 8 位为随机字符
 * </pre>
 * 一旦生成不可修改。
 */
@Component
public class PteidGenerator {

    private static final char[] CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    private final String prefix;

    public PteidGenerator(@Value("${pacc.pteid.prefix:PT}") String prefix) {
        this.prefix = prefix;
    }

    public String generate() {
        // 3-4 位时间戳编码：取 60 秒级时间的低 2 字节映射到 CHARS
        int ts = (int) (Instant.now().getEpochSecond() / 60L % 1296L);
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(CHARS[(ts / 36) % CHARS.length]);
        sb.append(CHARS[ts % CHARS.length]);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARS[random.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }
}