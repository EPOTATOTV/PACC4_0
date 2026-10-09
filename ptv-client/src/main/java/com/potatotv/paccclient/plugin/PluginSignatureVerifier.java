package com.potatotv.paccclient.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件 JAR 签名校验（文档 §2.3 约束 5、§2.4 步骤 2）。
 *
 * <p>用 {@link JarFile} 的验签能力：开启 verify 后完整读取一个条目即触发密码学校验，
 * 校验失败会抛 {@link SecurityException}；通过后 {@link JarEntry#getCertificates()} 给出签名者
 * 证书链。取叶子证书的 SHA-256 指纹与宿主信任锚比对，得出四种结论。</p>
 *
 * <p><b>信任锚来源</b>：生产构建应把 POTATOTV 签名证书指纹注入 {@code PACC_PLUGIN_TRUST} 或
 * 插件目录下的 {@code trusted-fingerprints.txt}。仓库不内置任何证书 —— 未配置信任锚时，
 * 即使 JAR 带签名也无法建立信任（返回 {@link Status#UNTRUSTED}），加载器据此拒绝。</p>
 */
public final class PluginSignatureVerifier {

    /** 待验签条目：签名应覆盖插件元信息。 */
    private static final String METADATA_ENTRY = "META-INF/pacc-plugin.json";

    /** 校验结论。 */
    public enum Status {
        /** 签名有效且签名者在信任锚内。 */
        TRUSTED,
        /** 无签名。 */
        UNSIGNED,
        /** 有签名但签名者不在信任锚内（或未配置信任锚）。 */
        UNTRUSTED,
        /** 签名损坏 / JAR 被篡改 / 读取失败。 */
        INVALID
    }

    /** 校验结果：结论 + 实际签名者指纹（无签名或读取失败时为 {@code null}）。 */
    public record Result(Status status, String fingerprint) {
    }

    private PluginSignatureVerifier() {
    }

    /** 校验一个插件 JAR。绝不抛出。 */
    public static Result verify(Path jar, Set<String> trustedFingerprints) {
        Set<String> trusted = trustedFingerprints == null ? Set.of() : trustedFingerprints;
        try (JarFile file = new JarFile(jar.toFile(), true)) {
            JarEntry entry = file.getJarEntry(METADATA_ENTRY);
            if (entry == null) {
                return new Result(Status.INVALID, null);
            }
            // 完整读完条目才会触发验签；读失败（被篡改）会抛 SecurityException
            try (InputStream in = file.getInputStream(entry)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            Certificate[] certs = entry.getCertificates();
            if (certs == null || certs.length == 0) {
                return new Result(Status.UNSIGNED, null);
            }
            String fingerprint = sha256Hex(certs[0].getEncoded());
            if (!trusted.isEmpty() && trusted.contains(fingerprint)) {
                return new Result(Status.TRUSTED, fingerprint);
            }
            return new Result(Status.UNTRUSTED, fingerprint);
        } catch (SecurityException e) {
            return new Result(Status.INVALID, null);
        } catch (IOException | CertificateEncodingException e) {
            return new Result(Status.INVALID, null);
        }
    }

    /** 从文本文件读取信任指纹：每行一个，{@code #} 起为注释。文件不存在返回空集。 */
    public static Set<String> loadTrustedFingerprints(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                out.add(value.toLowerCase());
            }
        } catch (IOException e) {
            return Set.of();
        }
        return out;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}