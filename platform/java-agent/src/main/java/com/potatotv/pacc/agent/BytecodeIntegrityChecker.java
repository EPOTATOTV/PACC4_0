package com.potatotv.pacc.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字节码完整性比对（设计文档 §5.2）。
 *
 * <p>对给定类名，取「类路径上的原始 class 字节」与「{@link ClassCaptureRegistry} 中
 * 运行时捕获的字节」做 SHA-256 比对。原始字节来源为
 * {@code ClassLoader.getResourceAsStream(name.replace('.','/') + ".class")}；
 * 探针自身包（{@code com/potatotv/pacc/agent/}）下的类不参与比对，以避免自检噪声，
 * 其余类（含由 AppClassLoader 装载的原版游戏类）均正常比对。</p>
 *
 * <p><b>方法级 diff 的诚实说明</b>：探针不含 ASM，因此不做完整字节码解析/反汇编。这里仅解析
 * class 文件的结构表（常量池 + 字段/方法表 + 属性长度），对每个方法按其原始属性字节求哈希，
 * 从而给出「新增 / 删除 / 变更」的方法名列表。<b>该 diff 基于方法属性字节，而非反编译语义</b>；
 * 若方法表无法解析（畸形 class），则仅报告类级哈希不一致并将该方法列表置空。</p>
 *
 * <p>原始字节缺失（动态生成类，如 {@code $Proxy}、Lambda 代理）时按「未知基线」处理，
 * 绝不判为篡改。</p>
 */
final class BytecodeIntegrityChecker {

    /** 方法 diff 列表的最大输出条数，避免超长报告。 */
    private static final int MAX_DIFFS = 25;

    /** 探针自身包前缀：仅此包下的类不参与完整性比对（避免自检噪声）。 */
    private static final String AGENT_PACKAGE_PREFIX = "com/potatotv/pacc/agent/";

    private BytecodeIntegrityChecker() {
    }

    /** 比对结果状态。 */
    enum Status {
        /** 运行时字节与类路径原件一致。 */
        EQUAL,
        /** 两者均存在且哈希不同 —— 判定为被篡改/重转换。 */
        MODIFIED,
        /** 类路径无原始字节（动态生成类），无法建立基线。 */
        UNKNOWN_BASELINE,
        /** 未捕获到运行时字节（类在转换器注册前已装载且未被重转换）。 */
        NO_RUNTIME_CAPTURE,
        /** 探针自身加载器或入参异常，跳过。 */
        ERROR
    }

    /**
     * 比对单个类。
     *
     * @param className 斜杠形式类名（{@code a/b/C}）
     * @param loader    该类的装载器（可为 null，表示引导类加载器）
     * @param registry  运行时字节码登记表
     * @return 比对结果（永不返回 null）
     */
    static BytecodeModification check(String className, ClassLoader loader, ClassCaptureRegistry registry) {
        if (className == null || className.isBlank()) {
            return new BytecodeModification(String.valueOf(className), Status.ERROR, null, null,
                    List.of(), "类名为空");
        }
        // 注：不能按「装载器 == 探针装载器」整体跳过 —— 原版 Minecraft 类同样由 AppClassLoader 装载，
        // 那样会把全部游戏类排除在完整性比对之外。这里改为只跳过探针自身包下的类。
        if (className.startsWith(AGENT_PACKAGE_PREFIX)) {
            return new BytecodeModification(className, Status.ERROR, null, null, List.of(),
                    "跳过探针自身类");
        }
        ClassCaptureRegistry.Capture capture = registry == null ? null : registry.get(className).orElse(null);
        byte[] original = readOriginal(className, loader);
        if (original == null) {
            return new BytecodeModification(className, Status.UNKNOWN_BASELINE, null,
                    capture == null ? null : Digest.sha256(capture.bytes()), List.of(),
                    "类路径无原始字节（动态生成类），按未知基线处理，非篡改");
        }
        if (capture == null) {
            return new BytecodeModification(className, Status.NO_RUNTIME_CAPTURE, Digest.sha256(original), null,
                    List.of(), "未捕获运行时字节");
        }
        String originalSha = Digest.sha256(original);
        String runtimeSha = Digest.sha256(capture.bytes());
        if (originalSha != null && originalSha.equals(runtimeSha)) {
            return new BytecodeModification(className, Status.EQUAL, originalSha, runtimeSha, List.of(), "一致");
        }
        Map<String, String> originalMethods = methodHashes(original);
        Map<String, String> runtimeMethods = methodHashes(capture.bytes());
        List<String> diffs = diffMethods(originalMethods, runtimeMethods);
        String note;
        if (originalMethods == null || runtimeMethods == null) {
            note = "方法表不可解析（畸形 class），仅报告类级哈希不一致";
        } else if (diffs.isEmpty()) {
            note = "方法属性字节一致，差异位于常量池或类级属性，未发现方法级改动";
        } else {
            note = "方法级差异基于方法属性字节（非反编译语义）";
        }
        return new BytecodeModification(className, Status.MODIFIED, originalSha, runtimeSha, diffs, note);
    }

    /**
     * 比对全部「已装载的监控目标类」。
     *
     * @param inst     探针持有的 {@link Instrumentation}（为 null 时返回空列表）
     * @param registry 运行时字节码登记表
     */
    static List<BytecodeModification> checkTargets(Instrumentation inst, ClassCaptureRegistry registry) {
        List<BytecodeModification> out = new ArrayList<>();
        if (inst == null) return out;
        Class<?>[] classes;
        try {
            classes = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            return out;
        }
        for (Class<?> c : classes) {
            try {
                String slash = c.getName().replace('.', '/');
                if (AgentTargets.matchClass(slash).isEmpty()) continue;
                out.add(check(slash, c.getClassLoader(), registry));
            } catch (Throwable ignore) {
                // 单个类比对失败不影响其余
            }
        }
        return out;
    }

    /** 从类路径读取原始 class 字节；缺失返回 null。 */
    private static byte[] readOriginal(String className, ClassLoader loader) {
        String resource = className.endsWith(".class") ? className : className + ".class";
        try (InputStream in = loader != null
                ? loader.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析 class 文件的 <em>结构表</em>（常量池 + 字段/方法表 + 属性长度），
     * 返回 {@code "方法名:描述符" -> 方法属性字节 SHA-256}。
     *
     * @return 解析结果；畸形 class 返回 null
     */
    private static Map<String, String> methodHashes(byte[] b) {
        if (b == null || b.length < 12) return null;
        if (!(b[0] == (byte) 0xCA && b[1] == (byte) 0xFE && b[2] == (byte) 0xBA && b[3] == (byte) 0xBE)) {
            return null;
        }
        try {
            Map<Integer, String> utf8 = new HashMap<>();
            int cpCount = u2(b, 8);
            int p = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p] & 0xFF;
                p++;
                switch (tag) {
                    case 1 -> { // Utf8
                        int len = u2(b, p);
                        p += 2;
                        utf8.put(i, new String(b, p, len, StandardCharsets.UTF_8));
                        p += len;
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 4; // int/float/ref/NameAndType/Dynamic
                    case 5, 6 -> { // long/double 占两项
                        p += 8;
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> p += 2; // Class/String/MethodType/Module/Package
                    case 15 -> p += 3; // MethodHandle
                    default -> {
                        return null;
                    }
                }
            }
            p += 2; // access_flags
            p += 2; // this_class
            p += 2; // super_class
            int interfaces = u2(b, p);
            p += 2 + interfaces * 2;

            p = skipFieldTable(b, p); // 字段表
            if (p < 0) return null;

            int methodCount = u2(b, p);
            p += 2;
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < methodCount; i++) {
                int methodStart = p;
                int nameIdx = u2(b, p + 2);
                int descIdx = u2(b, p + 4);
                int attrCount = u2(b, p + 6);
                int q = p + 8;
                for (int j = 0; j < attrCount; j++) {
                    q += 2; // attribute_name_index
                    int len = u4(b, q);
                    q += 4;
                    if (len < 0 || q + len > b.length) return null;
                    q += len;
                }
                String key = utf8.getOrDefault(nameIdx, "?") + ":" + utf8.getOrDefault(descIdx, "?");
                out.put(key, Digest.hex(Digest.sha256Digest().digest(Arrays.copyOfRange(b, methodStart, q))));
                p = q;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 跳过字段表（字段无 diff 需求，整表跳过即可；方法表需逐一哈希故单独处理）。
     *
     * @return 跳过后的偏移；越界返回 -1
     */
    private static int skipFieldTable(byte[] b, int p) {
        int count = u2(b, p);
        p += 2;
        for (int i = 0; i < count; i++) {
            p += 6; // access + name + descriptor
            int attrCount = u2(b, p);
            p += 2;
            for (int j = 0; j < attrCount; j++) {
                p += 2; // attribute_name_index
                int len = u4(b, p);
                p += 4;
                if (len < 0 || p + len > b.length) return -1;
                p += len;
            }
            if (p > b.length) return -1;
        }
        return p;
    }

    /** 生成方法级差异描述列表。 */
    private static List<String> diffMethods(Map<String, String> original, Map<String, String> runtime) {
        List<String> diffs = new ArrayList<>();
        if (original == null || runtime == null) return diffs;
        int omitted = 0;
        for (Map.Entry<String, String> e : original.entrySet()) {
            String key = e.getKey();
            if (!runtime.containsKey(key)) {
                if (diffs.size() < MAX_DIFFS) diffs.add("removed:" + key); else omitted++;
            } else if (!e.getValue().equals(runtime.get(key))) {
                if (diffs.size() < MAX_DIFFS) diffs.add("changed:" + key); else omitted++;
            }
        }
        for (String key : runtime.keySet()) {
            if (!original.containsKey(key)) {
                if (diffs.size() < MAX_DIFFS) diffs.add("added:" + key); else omitted++;
            }
        }
        if (omitted > 0) diffs.add("...(+ " + omitted + " more)");
        return diffs;
    }

    private static int u2(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int u4(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    /**
     * 单个类的字节码完整性结果。
     *
     * @param className       斜杠形式类名
     * @param status          比对状态
     * @param originalSha256  类路径原件哈希（无基线时为 null）
     * @param runtimeSha256   运行时捕获哈希（未捕获时为 null）
     * @param methodDiffs     方法级差异描述（无法解析时为空列表）
     * @param note            说明
     */
    record BytecodeModification(String className, Status status, String originalSha256, String runtimeSha256,
                                List<String> methodDiffs, String note) {

        /** 是否判定为被篡改。 */
        boolean modified() {
            return status == Status.MODIFIED;
        }
    }
}