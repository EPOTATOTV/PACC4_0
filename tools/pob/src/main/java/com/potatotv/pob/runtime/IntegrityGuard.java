package com.potatotv.pob.runtime;

import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 完整性校验自毁库。POB 只把它注入到 {@code enhance} 标记的类里，并替换 {@link #BLOB} 占位串。
 *
 * <p>被标记的类的 {@code <clinit>} 入口会调用 {@link #check()}。校验时读调用者自己的 class
 * 资源做 SHA-256，与生成期写入 BLOB 的期望值比对，不一致就自毁（抛异常）。系统不封禁玩家，
 * 自毁只影响本机当前进程。</p>
 *
 * <p>BLOB 结构：{@code 点分类名:sha256hex ";" ...}。生成期在注入调用之后、Guard 类构建之前
 * 才计算哈希，因此不存在「哈希包含自己」的循环——Guard 自身的字节不参与被校验类的哈希。</p>
 */
public final class IntegrityGuard {

    /** POB 生成时替换占位串。 */
    private static final String BLOB = "POBINTEGRITYGUARDBLOB";

    private IntegrityGuard() {
    }

    /** 在 enhance 类的 {@code <clinit>} 里被调用；哈希不符即自毁。 */
    public static void check() {
        try {
            Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
            String expected = expected(caller.getName());
            if (expected == null) {
                return;
            }
            if (!expected.equals(hash(caller))) {
                fail();
            }
        } catch (Throwable t) {
            fail();
        }
    }

    private static String expected(String className) {
        for (String entry : BLOB.split(";")) {
            int sep = entry.indexOf(':');
            if (sep > 0 && entry.substring(0, sep).equals(className)) {
                return entry.substring(sep + 1);
            }
        }
        return null;
    }

    private static String hash(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            byte[] out = digest.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }

    private static void fail() {
        throw new SecurityException("完整性校验失败");
    }
}