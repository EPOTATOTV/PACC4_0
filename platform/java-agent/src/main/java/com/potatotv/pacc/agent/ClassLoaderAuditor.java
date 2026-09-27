package com.potatotv.pacc.agent;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 类装载审计器（§5.1）：通过 {@link Instrumentation#getAllLoadedClasses()} 审计类装载现状。
 *
 * <p>检出项：</p>
 * <ol>
 *   <li><b>自定义装载器加载了游戏包根下的类</b>：非 bootstrap / platform / app 装载器却加载了
 *       {@link #GAME_PACKAGE_ROOTS} 中的类，常见于外挂自定义 ClassLoader 注入；</li>
 *   <li><b>来源不明的类</b>：无 code source、协议为网络（http/https/ftp）、或位于系统临时目录；</li>
 *   <li><b>作弊客户端标记类名</b>：命中 {@link #CHEAT_MARKERS}（Wurst/Impact/Sigma/Meteor/Aristois/BleachHack）；</li>
 *   <li><b>运行时字节码与类路径原件哈希不一致</b>：复用 {@link BytecodeIntegrityChecker} 判定。</li>
 * </ol>
 *
 * <p>全部逻辑包裹在 try/catch 中，单个类异常不影响整体，绝不抛出到游戏线程。</p>
 */
final class ClassLoaderAuditor {

    /**
     * 已知作弊客户端标记（类名/包名关键字，小写匹配）。
     *
     * <p>该列表为「文档化的常量」，可按 PTV 特征库灰度扩展；匹配采用子串而非全等，
     * 以覆盖 {@code net.wurstclient.*}、{@code me.zeroeightsix.aristois.*} 等命名空间前缀。</p>
     */
    static final List<String> CHEAT_MARKERS = List.of(
            "wurst", "impactclient", "impact-client", "sigma", "meteorclient", "meteor-client",
            "aristois", "bleachhack", "bleach", "novoline", "futureclient", "liquidbounce");

    /** 游戏类包根（Mojmap 与 MCP 命名均列入）。 */
    private static final List<String> GAME_PACKAGE_ROOTS = List.of(
            "net/minecraft/", "com/mojang/", "net/minecraftforge/", "net/fabricmc/");

    /** JDK 内部包前缀：这些类天然可能无 code source（jrt）或是运行时生成，不应作为可疑来源上报。 */
    private static final List<String> JDK_INTERNAL_PREFIXES = List.of(
            "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/", "org/xml/", "org/ietf/");

    /** 系统临时目录（小写）前缀，用于识别从临时目录装载的可疑类。 */
    private static final Set<String> TEMP_DIRS = buildTempDirs();

    private ClassLoaderAuditor() {
    }

    /**
     * 执行一次全量类装载审计。
     *
     * @param inst     探针持有的 {@link Instrumentation}（为 null 时返回空列表）
     * @param registry 运行时字节码登记表，用于完整性交叉校验（可为 null）
     * @return 按装载器聚合的审计结果
     */
    static List<ClassLoaderInfo> audit(Instrumentation inst, ClassCaptureRegistry registry) {
        List<ClassLoaderInfo> out = new ArrayList<>();
        if (inst == null) return out;
        Class<?>[] classes;
        try {
            classes = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            return out;
        }

        Map<ClassLoader, Accumulator> byLoader = new LinkedHashMap<>();
        for (Class<?> c : classes) {
            try {
                auditOne(c, registry, byLoader);
            } catch (Throwable ignore) {
                // 单类审计失败跳过
            }
        }
        for (Accumulator acc : byLoader.values()) {
            if (acc.suspiciousClasses.isEmpty()) continue;
            out.add(new ClassLoaderInfo(acc.loaderName, acc.category,
                    List.copyOf(acc.suspiciousClasses), List.copyOf(acc.reasons)));
        }
        return out;
    }

    /** 审计单个类并累加到对应装载器分组。 */
    private static void auditOne(Class<?> c, ClassCaptureRegistry registry, Map<ClassLoader, Accumulator> byLoader) {
        String name = c.getName().replace('.', '/');
        if (isNoise(name)) return;
        ClassLoader loader = c.getClassLoader();
        List<String> reasons = new ArrayList<>();

        if (matchesCheatMarker(name)) {
            reasons.add("cheat_marker:" + firstMarker(name));
        }
        String source = codeSourceOf(c);
        if (source == null) {
            reasons.add("no_code_source");
        } else {
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")) {
                reasons.add("remote_code_source:" + source);
            } else if (isTempPath(lower)) {
                reasons.add("temp_dir_code_source:" + source);
            }
        }
        if (isCustomLoader(loader) && isGameClass(name)) {
            reasons.add("custom_loader_on_game_class");
        }
        if (registry != null) {
            BytecodeIntegrityChecker.BytecodeModification mod =
                    BytecodeIntegrityChecker.check(name, loader, registry);
            if (mod.modified()) {
                reasons.add("runtime_bytecode_modified");
            }
        }
        if (reasons.isEmpty()) return;

        Accumulator acc = byLoader.computeIfAbsent(loader,
                l -> new Accumulator(loaderName(l), category(l)));
        acc.suspiciousClasses.add(name);
        for (String r : reasons) {
            if (!acc.reasons.contains(r)) acc.reasons.add(r);
        }
    }

    /** 类名是否命中已知作弊客户端标记（供转换器判断是否捕获运行时字节码）。 */
    static boolean matchesCheatMarker(String slashName) {
        if (slashName == null) return false;
        return firstMarker(slashName) != null;
    }

    /**
     * 是否应忽略该类：数组类、JDK 内部包、运行时生成的隐藏类（{@code /0x…}）、Lambda 合成类等，
     * 这些类天然可能「无 code source」或数量庞大，不应作为可疑来源上报，否则会淹没真实信号。
     */
    private static boolean isNoise(String slashName) {
        if (slashName.startsWith("[")) return true;
        if (slashName.indexOf("/0x") >= 0) return true;
        if (slashName.contains("$$Lambda")) return true;
        if (slashName.contains("$LambdaForm")) return true;
        for (String prefix : JDK_INTERNAL_PREFIXES) {
            if (slashName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String firstMarker(String slashName) {
        String lower = slashName.toLowerCase(Locale.ROOT);
        for (String m : CHEAT_MARKERS) {
            if (lower.contains(m)) return m;
        }
        return null;
    }

    /** 是否游戏包根下的类。 */
    private static boolean isGameClass(String slashName) {
        for (String root : GAME_PACKAGE_ROOTS) {
            if (slashName.startsWith(root)) return true;
        }
        return false;
    }

    /** 是否为自定义装载器（排除 bootstrap / platform / app / 探针自身）。 */
    private static boolean isCustomLoader(ClassLoader loader) {
        if (loader == null) return false; // bootstrap
        if (loader == ClassLoader.getPlatformClassLoader()) return false;
        if (loader == ClassLoader.getSystemClassLoader()) return false;
        return loader != PaccJavaAgent.class.getClassLoader();
    }

    /** 装载器分类标签。 */
    private static String category(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        if (loader == ClassLoader.getPlatformClassLoader()) return "platform";
        if (loader == ClassLoader.getSystemClassLoader()) return "app";
        if (loader == PaccJavaAgent.class.getClassLoader()) return "agent";
        return "custom";
    }

    /** 装载器可读名称（含其 URL 列表）。 */
    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        StringBuilder sb = new StringBuilder(loader.getClass().getName());
        sb.append('@').append(Integer.toHexString(System.identityHashCode(loader)));
        if (loader instanceof java.net.URLClassLoader ucl) {
            try {
                URL[] urls = ucl.getURLs();
                if (urls.length > 0) {
                    sb.append(' ');
                    for (int i = 0; i < urls.length && i < 5; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(urls[i]);
                    }
                    if (urls.length > 5) sb.append(",...");
                }
            } catch (Throwable ignore) {
                // 忽略不可枚举的装载器
            }
        }
        return sb.toString();
    }

    /** 类的 code source 字符串（文件路径或 URL）；不可得返回 null。 */
    private static String codeSourceOf(Class<?> c) {
        try {
            ProtectionDomain pd = c.getProtectionDomain();
            if (pd == null) return null;
            CodeSource cs = pd.getCodeSource();
            if (cs == null) return null;
            if (cs.getLocation() != null) return cs.getLocation().toString();
            return cs.getCertificates() != null ? "signed:card=" + cs.getCertificates().length : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTempPath(String lowerSource) {
        for (String dir : TEMP_DIRS) {
            if (lowerSource.contains(dir)) return true;
        }
        return false;
    }

    private static Set<String> buildTempDirs() {
        Set<String> set = new LinkedHashSet<>();
        addTempDir(set, System.getProperty("java.io.tmpdir"));
        String tmp = System.getenv("TEMP");
        addTempDir(set, tmp);
        String tmpdir = System.getenv("TMPDIR");
        addTempDir(set, tmpdir);
        return set;
    }

    private static void addTempDir(Set<String> set, String dir) {
        if (dir == null || dir.isBlank()) return;
        try {
            Path p = Path.of(dir).toAbsolutePath().normalize();
            String s = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (s.length() > 2) set.add(s);
        } catch (Throwable ignore) {
            // 忽略非法路径
        }
    }

    /**
     * 按装载器聚合的审计结果。
     *
     * @param loader            装载器可读名称
     * @param category          装载器分类（bootstrap/platform/app/agent/custom）
     * @param suspiciousClasses 可疑类名（斜杠形式）
     * @param reasons           判定原因列表
     */
    record ClassLoaderInfo(String loader, String category, List<String> suspiciousClasses, List<String> reasons) {
    }

    /** 审计累加器（内部可变）。 */
    private static final class Accumulator {
        private final String loaderName;
        private final String category;
        private final Set<String> suspiciousClasses = new LinkedHashSet<>();
        private final List<String> reasons = new ArrayList<>();

        private Accumulator(String loaderName, String category) {
            this.loaderName = loaderName;
            this.category = category;
        }
    }
}