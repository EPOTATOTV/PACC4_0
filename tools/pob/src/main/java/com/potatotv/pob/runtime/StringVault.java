package com.potatotv.pob.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 字符串解密库。这份源码会被 POB 编译进自己，然后在混淆时以「类字节」的形态注入产物。
 *
 * <p>注入时 POB 只做两件事：把 {@link #BLOB} 的占位串换成真正的加密数据，并把类名改成目标包下的
 * 固定名。因此这里不能出现对自身类名的引用（不用 {@code StringVault.class}、不读自己的资源），
 * 所有逻辑都必须只依赖 JDK。</p>
 *
 * <p>BLOB 结构：{@code saltHex ";" record ";" record ...}，其中每条 record 是
 * {@code ivHex(32) + cipherHex}，record 的下标就是调用 {@link #get(int)} 时传入的索引。
 * 密钥由 salt 与该索引派生，因此生成侧与运行侧不需要共享任何常量。</p>
 */
public final class StringVault {

    /** POB 生成时替换的下标记。写成字面量的常量，编译后落在常量池里等待被换掉。 */
    private static final String BLOB = "POBSTRINGVAULTBLOB";

    private static final String[] VALUES = load();

    private StringVault() {
    }

    /** 返回索引对应的明文字符串；intern 保证与源码里的字符串字面量具有同样的引用同一性。 */
    public static String get(int index) {
        String value = VALUES[index];
        return value == null ? null : value.intern();
    }

    private static String[] load() {
        try {
            String blob = BLOB;
            int first = blob.indexOf(';');
            byte[] salt = hex(blob.substring(0, first));
            String[] records = blob.substring(first + 1).split(";", -1);
            String[] values = new String[records.length];
            for (int i = 0; i < records.length; i++) {
                byte[] iv = hex(records[i].substring(0, 32));
                byte[] cipher = hex(records[i].substring(32));
                values[i] = new String(decrypt(salt, iv, i, cipher), StandardCharsets.UTF_8);
            }
            return values;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("字符串解密失败", e);
        }
    }

    private static byte[] decrypt(byte[] salt, byte[] iv, int index, byte[] cipher) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        digest.update((byte) ':');
        digest.update(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
        byte[] key = new byte[16];
        System.arraycopy(digest.digest(), 0, key, 0, 16);
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return aes.doFinal(cipher);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}