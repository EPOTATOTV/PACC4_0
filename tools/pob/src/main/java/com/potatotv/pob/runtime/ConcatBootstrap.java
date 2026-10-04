package com.potatotv.pob.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 字符串拼接引导方法。这份源码会被 POB 编译进自己，然后在混淆时以「类字节」的形态注入产物。
 *
 * <p>javac 9+ 会把 {@code a + "/api/x"} 编译成 {@code invokedynamic makeConcatWithConstants}，
 * 其中字面量直接写进引导实参的 recipe 字符串里，落在 BootstrapMethods 属性中——普通的 ldc
 * 加密碰不到它。POB 的做法是把 recipe 换成密文（仍是 {@code CONSTANT_String}，保持引导实参
 * 的类型不变），并把 {@code bootstrap_method_ref} 指到本类的 {@link #bootstrap}：链接时先解密
 * recipe，再转交 JDK 的 {@code StringConcatFactory} 完成真正的拼接。</p>
 *
 * <p>注入时 POB 只做两件事：把 {@link #KEY} 的占位串换成真正随机 salt，并把类名改成目标包下的
 * 固定名。因此这里不能出现对自身类名的引用，所有逻辑只依赖 JDK。</p>
 *
 * <p>recipe 密文结构：{@code ivHex(32) + cipherHex}，密钥由 salt 与索引 {@code 0} 派生，
 * 与 {@code PobVault} 同一套 AES-CTR 方案，只是配方不进 BLOB（不占它的容量）。</p>
 */
public final class ConcatBootstrap {

    /** POB 生成时替换的占位串（32 个十六进制字符 = 16 字节 salt）。 */
    private static final String KEY = "POBCONCATSALTPLACEHOLDER";

    private ConcatBootstrap() {
    }

    /**
     * 引导方法签名必须与 JDK 的 {@code makeConcatWithConstants} 完全一致，这样引导实参
     * （recipe 明文位换成密文）不需要任何类型转换即可原样转交。
     */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type,
                                     String recipe, Object[] constants) throws Throwable {
        return StringConcatFactory.makeConcatWithConstants(lookup, name, type, decrypt(recipe), constants);
    }

    private static String decrypt(String blob) {
        try {
            byte[] salt = hex(KEY);
            byte[] iv = hex(blob.substring(0, 32));
            byte[] cipher = hex(blob.substring(32));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((byte) ':');
            digest.update((byte) '0');
            byte[] key = new byte[16];
            System.arraycopy(digest.digest(), 0, key, 0, 16);
            Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(aes.doFinal(cipher), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("字符串拼接配方解密失败", e);
        }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
