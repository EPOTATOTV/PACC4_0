package com.potatotv.paccclient.store;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.KeySpec;

/**
 * PBKDF2 密钥派生：口令 = 设备指纹 + PTEID，盐由每文件随机生成（见 {@link LocalSecureStore}），
 * 迭代次数对本机场景足够（非网络服务，无需高成本对抗在线爆破）。
 */
public final class KeyDeriver {

    public static final int KEY_BYTES = 32;
    public static final int ITERATIONS = 120_000;

    private KeyDeriver() {
    }

    /** 派生 32 字节 AES-256 密钥。 */
    public static SecretKey derive(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BYTES * 8);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}