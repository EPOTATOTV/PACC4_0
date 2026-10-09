package com.potatotv.paccclient.store;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 本地加密存储（纯 JDK，零第三方运行时依赖）。
 * <p>文件格式：{magic:"PACC"}{version:1}{saltLen:4}{salt}{ivLen:4}{iv(12)}
 * {ciphertext + GCM tag}。AES-256-GCM，密钥由 PBKDF2（口令=设备指纹+PTEID，每文件随机盐）派生。</p>
 * <p>威胁模型：本机可读加密——防止存储文件被随手拷贝后明文/改值泄漏，并非 KMS 级密钥保护。
 * 解密或认证任一失败即视为数据被篡改。</p>
 */
public final class LocalSecureStore {

    private static final byte[] MAGIC = {'P', 'A', 'C', 'C'};
    private static final int VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    /** 配置敏感值落盘前缀：该值为加密容器。 */
    public static final String PREFIX = "enc:";

    private static final SecureRandom RAND = new SecureRandom();

    private LocalSecureStore() {
    }

    /** 加密写盘：生成新盐 + 新 IV，含 magic/version 头。 */
    public static void save(Path file, String password, byte[] plaintext) throws IOException {
        byte[] blob = encryptBytes(plaintext, password);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, blob);
    }

    /** 解密读盘；任何解析或认证失败抛出 IOException（视为损坏/被篡改）。 */
    public static byte[] load(Path file, String password) throws IOException {
        return loadBytes(Files.readAllBytes(file), password);
    }

    /** 内存解密核心：解析 magic/version/长度并认证解密；解析或认证失败抛 IOException。 */
    public static byte[] loadBytes(byte[] blob, String password) throws IOException {
        if (blob.length < 4 + 1 + 4 + 4 + IV_LEN + 16) {
            throw new IOException("本地数据长度非法");
        }

        ByteBuffer bb = ByteBuffer.wrap(blob);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("非 PACC 本地数据");
        int version = bb.get() & 0xFF;
        if (version != VERSION) throw new IOException("本地数据版本不支持");

        int saltLen = bb.getInt();
        int ivLen = bb.getInt();
        if (saltLen <= 0 || saltLen > 128 || ivLen <= 0 || ivLen > 64) {
            throw new IOException("本地数据被篡改");
        }
        byte[] salt = new byte[saltLen];
        bb.get(salt);
        byte[] iv = new byte[ivLen];
        bb.get(iv);
        byte[] cipher = new byte[bb.remaining()];
        bb.get(cipher);
        return decrypt(KeyDeriver.derive(password, salt), iv, cipher);
    }

    /** 将明文加密封装为 "enc:" + base64 容器，供配置敏感值使用。 */
    public static String encryptString(String plain, String password) throws IOException {
        byte[] blob = encryptBytes(plain.getBytes(StandardCharsets.UTF_8), password);
        return PREFIX + Base64.getEncoder().encodeToString(blob);
    }

    /** 解密 "enc:" 值；非 "enc:" 前缀原样返回（兼容明文）。解析/认证失败抛 IOException。 */
    public static String decryptString(String value, String password) throws IOException {
        if (!value.startsWith(PREFIX)) return value;
        byte[] blob = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        return new String(loadBytes(blob, password), StandardCharsets.UTF_8);
    }

    private static byte[] encryptBytes(byte[] plain, String password) throws IOException {
        byte[] salt = randomBytes(SALT_LEN);
        byte[] iv = randomBytes(IV_LEN);
        byte[] cipher = encrypt(KeyDeriver.derive(password, salt), iv, plain);
        return compose(salt, iv, cipher);
    }

    private static byte[] compose(byte[] salt, byte[] iv, byte[] cipher) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(VERSION);
        writeInt(out, salt.length);
        writeInt(out, iv.length);
        out.writeBytes(salt);
        out.writeBytes(iv);
        out.writeBytes(cipher);
        return out.toByteArray();
    }

    /** 编码字符串列表（长度前缀），供队列/状态持久化使用。 */
    public static byte[] encodeStrings(List<String> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, values.size());
        for (String s : values) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeInt(out, b.length);
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    /** 解码 {@link #encodeStrings} 的产物；越界/非法即视为被篡改。 */
    public static List<String> decodeStrings(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        int n = bb.getInt();
        if (n < 0 || n > 100_000) throw new IOException("本地数据被篡改");
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (bb.remaining() < 4) throw new IOException("本地数据被篡改");
            int len = bb.getInt();
            if (len < 0 || len > bb.remaining()) throw new IOException("本地数据被篡改");
            byte[] b = new byte[len];
            bb.get(b);
            list.add(new String(b, StandardCharsets.UTF_8));
        }
        return list;
    }

    private static byte[] encrypt(SecretKey key, byte[] iv, byte[] plain) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IOException("加密失败", e);
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] iv, byte[] cipher) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(cipher);
        } catch (GeneralSecurityException e) {
            throw new IOException("本地数据已损坏或被篡改", e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RAND.nextBytes(b);
        return b;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}