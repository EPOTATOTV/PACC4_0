package com.potatotv.pacc.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 开放 API 密钥的静态加密：以平台主密钥（PACC_API_MASTER_SECRET，SHA-256 派生 AES-128）对
 * 密钥明文做 AES-GCM 加密后落库，库泄露不含可用明文。输出 = base64(12B IV || 密文+tag)。
 */
public final class ApiCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiCrypto() {
    }

    public static String encrypt(String plain, String masterSecret) {
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(masterSecret), new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API 密钥加密失败", e);
        }
    }

    public static String decrypt(String encoded, String masterSecret) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            byte[] enc = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, enc, 0, enc.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(masterSecret), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API 密钥解密失败", e);
        }
    }

    private static SecretKeySpec key(String masterSecret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((masterSecret == null ? "" : masterSecret).getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(digest, 0, key, 0, 16);
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("主密钥派生失败", e);
        }
    }
}