package com.potatotv.pacc.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Mod 签名校验器：定位已装载的 mod jar 并逐个校验其 JAR 签名（§5.1）。
 *
 * <p>发现来源：</p>
 * <ol>
 *   <li>{@code java.class.path} 中文件名疑似 mod 的 jar（含 mod / forge / fabric / optifine 等关键字）；</li>
 *   <li>工作目录（及 {@code -Dpacc.agent.gamedir} 指定目录）下的 {@code mods/} 目录内全部 jar；</li>
 *   <li>已装载类所用 {@link URLClassLoader} 暴露的 jar（可覆盖 1.8 Forge 的自定义装载器）。</li>
 * </ol>
 *
 * <p>校验方式：以 {@link JarFile}（默认启用签名校验）打开，若存在 {@code META-INF/*.SF} 视为已签名，
 * 再逐条目读取全文触发校验；读取抛出 {@code SecurityException} 即判定签名无效，否则从
 * {@code JarEntry.getCodeSigners()} 取签名者。允许清单来自探针 jar 同级的 {@code pacc-agent-signers.txt}
 * （或 {@code -Dpacc.agent.signers.file}）；<b>文件缺失即视为「未配置允许清单」，不以此单独判为可疑</b>。</p>
 *
 * <p>可疑判定：未签名 / 签名无效 / 已配置允许清单但签名者不在清单内。</p>
 */
final class ModSignatureVerifier {

    /** 类路径中「疑似 mod」的文件名关键字（小写匹配）。 */
    private static final List<String> MOD_HINTS = List.of(
            "mod", "forge", "fabric", "quilt", "optifine", "liteloader", "hack", "client", "injector");

    private ModSignatureVerifier() {
    }

    /**
     * 发现并校验所有疑似 mod 的 jar。
     *
     * @param inst 探针持有的 {@link Instrumentation}，可为 null（仅用类路径 + mods 目录发现）
     * @return 每个候选 jar 一条 {@link ModInfo}；单个 jar 解析失败时跳过，绝不抛出
     */
    static List<ModInfo> verify(Instrumentation inst) {
        Set<Path> jars = new LinkedHashSet<>();
        collectClasspathJars(jars);
        collectModsDir(jars);
        collectLoaderJars(inst, jars);

        Set<String> allowList = loadAllowList(); // null = 未配置允许清单
        List<ModInfo> out = new ArrayList<>();
        for (Path p : jars) {
            try {
                out.add(inspect(p, allowList));
            } catch (Throwable t) {
                // 单个 jar 失败不影响其余
            }
        }
        return out;
    }

    /** 扫描 {@code java.class.path}。 */
    private static void collectClasspathJars(Set<Path> jars) {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;
            try {
                Path p = Path.of(entry).toAbsolutePath();
                if (isCandidate(p)) jars.add(p.normalize());
            } catch (Throwable ignore) {
                // 非法路径跳过
            }
        }
    }

    /** 扫描工作目录及可选游戏目录下的 {@code mods/}。 */
    private static void collectModsDir(Set<Path> jars) {
        List<Path> dirs = new ArrayList<>();
        try {
            dirs.add(Path.of(System.getProperty("user.dir", "."), "mods"));
        } catch (Throwable ignore) {
            // 忽略
        }
        String gamedir = System.getProperty("pacc.agent.gamedir");
        if (gamedir != null && !gamedir.isBlank()) {
            try {
                dirs.add(Path.of(gamedir, "mods"));
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(Path::toAbsolutePath)
                        .filter(ModSignatureVerifier::isJar)
                        .forEach(jars::add);
            } catch (Throwable ignore) {
                // 目录不可读跳过
            }
        }
    }

    /** 从已装载类的 URLClassLoader 中补充 jar（覆盖 Forge 等自定义装载器）。 */
    private static void collectLoaderJars(Instrumentation inst, Set<Path> jars) {
        if (inst == null) return;
        Set<ClassLoader> seen = new HashSet<>();
        Class<?>[] classes;
        try {
            classes = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            return;
        }
        for (Class<?> c : classes) {
            try {
                ClassLoader loader = c.getClassLoader();
                if (!(loader instanceof URLClassLoader ucl) || !seen.add(loader)) continue;
                for (URL url : ucl.getURLs()) {
                    if (!"file".equalsIgnoreCase(url.getProtocol())) continue;
                    Path p = Path.of(url.toURI()).toAbsolutePath();
                    if (isCandidate(p)) jars.add(p.normalize());
                }
            } catch (Throwable ignore) {
                // 单个装载器/URL 失败跳过
            }
        }
    }

    /** 校验单个 jar。 */
    private static ModInfo inspect(Path path, Set<String> allowList) {
        String name = stripJarSuffix(path.getFileName().toString());
        String sha256 = Digest.sha256File(path);
        boolean signed = false;
        boolean validSignature = false;
        String signer = null;

        try (JarFile jar = new JarFile(path.toFile())) {
            List<JarEntry> entries = Collections.list(jar.entries());
            for (JarEntry e : entries) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("meta-inf/") && n.endsWith(".sf")) {
                    signed = true;
                    break;
                }
            }
            if (signed) {
                validSignature = true;
                for (JarEntry e : entries) {
                    if (e.isDirectory()) continue;
                    try (InputStream in = jar.getInputStream(e)) {
                        in.readAllBytes(); // 触发 JAR 签名校验，内容被篡改时抛 SecurityException
                    } catch (SecurityException se) {
                        validSignature = false;
                        break;
                    }
                    CodeSigner[] signers = e.getCodeSigners();
                    if (signer == null && signers != null && signers.length > 0
                            && !signers[0].getSignerCertPath().getCertificates().isEmpty()) {
                        java.security.cert.Certificate cert =
                                signers[0].getSignerCertPath().getCertificates().get(0);
                        signer = cert instanceof java.security.cert.X509Certificate x509
                                ? x509.getSubjectX500Principal().getName()
                                : cert.toString();
                    }
                }
            }
        } catch (Throwable t) {
            // 无法打开/读取（含签名损坏）一律判为签名无效
            validSignature = false;
        }

        String reason;
        boolean suspicious;
        if (!signed) {
            reason = "unsigned";
            suspicious = true;
        } else if (!validSignature) {
            reason = "invalid_signature";
            suspicious = true;
        } else if (allowList != null && signer == null) {
            reason = "signer_unresolved";
            suspicious = true;
        } else if (allowList != null && !allowed(allowList, signer)) {
            reason = "signer_not_allowed:" + signer;
            suspicious = true;
        } else if (allowList == null) {
            reason = "signed_no_allowlist";
            suspicious = false;
        } else {
            reason = "ok";
            suspicious = false;
        }
        return new ModInfo(name, path.toString(), sha256, signed, validSignature, signer, suspicious, reason);
    }

    /** 文件名为 jar 且命中 mod 关键字。 */
    private static boolean isCandidate(Path p) {
        if (!isJar(p)) return false;
        String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String hint : MOD_HINTS) {
            if (fn.contains(hint)) return true;
        }
        return false;
    }

    private static boolean isJar(Path p) {
        return p != null && p.getFileName() != null
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String stripJarSuffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    /** 允许清单匹配：忽略大小写，支持全等或「签名者 DN 包含清单条目」两种。 */
    private static boolean allowed(Set<String> allowList, String signer) {
        if (signer == null) return false;
        String s = signer.toLowerCase(Locale.ROOT);
        for (String entry : allowList) {
            if (s.equals(entry) || s.contains(entry)) return true;
        }
        return false;
    }

    /**
     * 载入签名者允许清单。
     *
     * @return 允许清单（小写）；文件缺失/不可读时返回 null，表示「未配置」，调用方不应据此判可疑
     */
    private static Set<String> loadAllowList() {
        Path file = allowListFile();
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            Set<String> set = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                set.add(s.toLowerCase(Locale.ROOT));
            }
            return set;
        } catch (IOException e) {
            return null;
        }
    }

    /** 允许清单文件位置：优先系统属性，其次探针 jar 同级目录。 */
    private static Path allowListFile() {
        String prop = System.getProperty("pacc.agent.signers.file");
        if (prop != null && !prop.isBlank()) {
            try {
                return Path.of(prop);
            } catch (Throwable ignore) {
                // 回退到默认位置
            }
        }
        try {
            URI self = PaccJavaAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path dir = Path.of(self).getParent();
            if (dir != null) return dir.resolve("pacc-agent-signers.txt");
        } catch (Throwable ignore) {
            // 回退到工作目录
        }
        return Path.of("pacc-agent-signers.txt");
    }

    /**
     * 单个 mod 的签名校验结果。
     *
     * @param name            mod 名（jar 文件名去扩展名）
     * @param path            jar 绝对路径
     * @param sha256          文件 SHA-256（读取失败为 null）
     * @param signed          是否包含 JAR 签名（{@code META-INF/*.SF}）
     * @param validSignature  签名是否有效
     * @param signer          首位签名者 DN（未签名/无法解析为 null）
     * @param suspicious      是否可疑
     * @param reason          判定原因（unsigned / invalid_signature / signer_not_allowed / …）
     */
    record ModInfo(String name, String path, String sha256, boolean signed, boolean validSignature,
                   String signer, boolean suspicious, String reason) {
    }
}