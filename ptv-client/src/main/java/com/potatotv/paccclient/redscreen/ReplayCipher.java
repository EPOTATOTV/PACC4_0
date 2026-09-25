package com.potatotv.paccclient.redscreen;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * 回放录像的加密封装（文档 §7.3：加密存储，仅授权可查看）。
 *
 * <p>信封加密：每条录像随机生成 AES-256 密钥与 12 字节 IV，用 AES-GCM 加密录像字节；
 * 密文落盘、密钥随元数据提交给服务端（走 HTTPS + 玩家 JWT）。这样磁盘上永远只有密文，
 * 解密能力由服务端的访问控制（管理员权限 + 审计）决定，而不是靠一个会随部署漂移的固定密钥。</p>
 *
 * <p>GCM 的认证标签一并写入密文尾部，解密时校验，录像被改动会直接解密失败。</p>
 */
final class ReplayCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private ReplayCipher() {
    }

    /** 加密结果：密文（含标签）+ 密钥 + IV（后两者随元数据上报）。 */
    record Sealed(byte[] cipher, byte[] key, byte[] iv) {
    }

    /** 生成随机密钥并加密。 */
    static Sealed seal(byte[] plain) {
        try {
            KeyGenerator gen = KeyGenerator.getInstance(ALGORITHM);
            gen.init(KEY_BITS, new SecureRandom());
            SecretKey key = gen.generateKey();
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new Sealed(cipher.doFinal(plain), key.getEncoded(), iv);
        } catch (Exception e) {
            throw new IllegalStateException("录像加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密（服务端与测试使用同一实现）。 */
    static byte[] open(byte[] cipherText, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("录像解密失败: " + e.getMessage(), e);
        }
    }
}