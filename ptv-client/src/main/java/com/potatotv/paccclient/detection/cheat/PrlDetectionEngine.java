package com.potatotv.paccclient.detection.cheat;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.sandbox.PrlHostContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 玩家端规则引擎，替代原先硬编码的 {@link CheatRuleRegistry}（设计文档 §3.2.2）。
 *
 * <p>24 条端侧检测规则改为 PRL 脚本，随包放在 {@code rules/*.prl}，文件名即作弊类型 code。
 * 规则由自研 VM 解释执行，宿主只暴露特征读取 —— 与旧实现相比，行为必须逐分逐证据键一致，
 * 这一点由 {@code PrlDetectionEngineTest} 对着迁移前 Java 规则产出的金标钉住。</p>
 *
 * <p><b>热更新。</b>{@link #updateRule} 先编译再原子替换：编译失败抛异常且旧规则原样保留，
 * 与设计文档 §3.2.3 步骤 4「编译校验（失败则保留旧规则）」一致。缓存与灰度属于服务端下发的
 * 那一层，放在这里只会让规则来源变多、更难解释一条规则为什么是这个版本。</p>
 */
public final class PrlDetectionEngine {

    /** 规则资源目录（jar 内与开发期的 target/classes 下同名）。 */
    private static final String RULES_DIR = "rules";
    private static final String RULES_PREFIX = RULES_DIR + "/";
    private static final String RULE_SUFFIX = ".prl";

    /** emit_alert 证据里承载 0-100 分值的键。 */
    private static final String EVIDENCE_SCORE = "score";
    /** emit_alert 证据里承载证据特征键列表的键。 */
    private static final String EVIDENCE_KEYS = "keys";

    /**
     * 迁移前的规则注册顺序（原 {@code CheatRuleRegistry.defaultRules()}）。
     *
     * <p>同分命中时按它定序，而不是按规则名字典序：{@code BruteForceDetector} 拿的是
     * {@code hits.get(0)} 并把它的类型当作上报的作弊类型，同分时选谁直接决定上报内容。
     * 多条规则撞到同一个分值并非理论问题 —— invmanager 与 velocity 的上限都是 85。</p>
     */
    private static final List<CheatType> LEGACY_ORDER = List.of(
            CheatType.SCAFFOLD, CheatType.FASTPLACE, CheatType.FASTBREAK, CheatType.NUKER,
            CheatType.CRITICALS, CheatType.VELOCITY, CheatType.NOFALL, CheatType.STEP,
            CheatType.SPRINT, CheatType.AUTOBLOCK, CheatType.CHESTSTEALER, CheatType.INVMANAGER,
            CheatType.AUTOARMOR, CheatType.FASTEAT, CheatType.BLINK,
            CheatType.MEMORY_REGION, CheatType.MODULE_INJECTION, CheatType.DEBUGGER_PRESENT,
            CheatType.INJECTOR_TOOL, CheatType.VIRTUAL_MACHINE, CheatType.KERNEL_HOOK,
            CheatType.CLICK_REGULARITY, CheatType.TRAJECTORY_ANOMALY, CheatType.REACTION_TIME,
            // 三层检测架构批次：同样按「网络 → 屏幕 → 系统」的登记顺序参与同分定序
            CheatType.NET_SPEED_ANOMALY, CheatType.NET_FLY_ANOMALY, CheatType.NET_TELEPORT,
            CheatType.NET_PACKET_TAMPER, CheatType.VISION_AIMBOT, CheatType.VISION_ESP,
            CheatType.ONBOARD_MACRO, CheatType.INJECTED_CLIENT, CheatType.KERNEL_CALLBACK,
            CheatType.UNSIGNED_EXECUTABLE);

    private final RuleManager manager;
    private final PrlCompiler compiler;

    public PrlDetectionEngine() {
        this(new ClientRuleHostContext());
    }

    /** 注入自定义宿主（便于单测）。 */
    public PrlDetectionEngine(PrlHostContext host) {
        this.manager = new RuleManager(host);
        this.compiler = new PrlCompiler(host);
    }

    // ------------------------------------------------------------------ 装载

    /**
     * 装载随包内置的全部规则，返回成功条数。
     *
     * <p>单条规则加载失败不影响其余规则：端侧一旦因为一条规则写错就整体不检测，比少一条规则严重得多。</p>
     */
    public int loadBuiltin() {
        int loaded = 0;
        for (String name : builtinRuleNames()) {
            if (CheatType.fromCode(name).isEmpty()) {
                // 认得出来的名字才装：规则名即作弊类型 code，装一条认不出的规则只会产出永远被丢弃的告警。
                System.err.println("[PTV-PRL] 跳过不是已知作弊类型的规则文件: " + name + RULE_SUFFIX);
                continue;
            }
            String source = readBuiltin(name);
            if (source == null) {
                continue;
            }
            try {
                manager.loadRule(name, source);
                loaded++;
            } catch (RuntimeException e) {
                System.err.println("[PTV-PRL] 内置规则加载失败 " + name + ": " + e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * 热更新一条规则：编译通过才替换，编译失败抛异常且旧版本原样保留。
     *
     * @return 编译后的规则名
     * @throws IllegalArgumentException 规则名不是已知作弊类型 code
     * @throws com.potatotv.prl.PrlException 语法/类型/白名单校验未通过
     */
    public String updateRule(String name, String source) {
        if (CheatType.fromCode(name).isEmpty()) {
            throw new IllegalArgumentException("规则名必须是已知作弊类型 code：" + name);
        }
        manager.loadRule(name, source);
        return name;
    }

    /** 卸载一条规则；返回是否真的卸掉了。 */
    public boolean removeRule(String name) {
        return manager.unloadRule(name);
    }

    /** 当前已装载的规则名，字典序。 */
    public List<String> ruleNames() {
        return manager.ruleNames();
    }

    public int size() {
        return manager.size();
    }

    // ------------------------------------------------------------------ 求值

    /** 执行全部规则，返回达到上报阈值的命中，按分值降序。 */
    public List<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        if (fv == null || manager.size() == 0) {
            return List.of();
        }
        List<DetectionResult> alerts = manager.executeAll(toRuleInput(fv, ctx));
        List<CheatFinding> hits = new ArrayList<>();
        for (DetectionResult alert : alerts) {
            toFinding(alert).ifPresent(hits::add);
        }
        // 分值降序；同分按迁移前的注册顺序定序，未知类型排在最后，避免依赖哈希/装载顺序导致输出飘。
        hits.sort((a, b) -> a.score() != b.score()
                ? Integer.compare(b.score(), a.score())
                : Integer.compare(LEGACY_ORDER.indexOf(a.type()), LEGACY_ORDER.indexOf(b.type())));
        return hits;
    }

    /** 分值最高的一条命中。 */
    public Optional<CheatFinding> evaluateTop(FeatureVector fv, AnalysisContext ctx) {
        List<CheatFinding> hits = evaluate(fv, ctx);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * 把告警转成端侧命中结果。
     *
     * <p>规则里没有的作弊类型会被跳过：规则可以热更新，type 是外部输入，宁可少报一条也不能
     * 造一个 {@link CheatType} 出来。</p>
     */
    private Optional<CheatFinding> toFinding(DetectionResult alert) {
        Optional<CheatType> type = CheatType.fromCode(alert.ruleName());
        if (type.isEmpty()) {
            System.err.println("[PTV-PRL] 规则 " + alert.ruleName() + " 的 type 不是已知作弊类型，已跳过");
            return Optional.empty();
        }
        Map<Object, Object> evidence = alert.evidence() == null ? Map.of() : alert.evidence();
        // 与迁移前一致地「截断」而不是四舍五入：旧规则里连续量算出的分值是 (int) 强转
        // （如 fastplace 的 (int) Math.min(85, 40 + (perSec - 2) * 8)）。四舍五入会在非整数速率上
        // 多给 1 分，正好能跨过 70 分的 severity 分档，属于实打实的行为变化。
        int score = (int) asDouble(evidence.get(EVIDENCE_SCORE));
        List<String> keys = asStringList(evidence.get(EVIDENCE_KEYS));
        return Optional.of(CheatFinding.of(type.get(), score, keys.toArray(new String[0])))
                .filter(CheatFinding::reportable);
    }

    /**
     * 构造规则 input。
     *
     * <p>{@code features} 按 schema 铺满全部维度再用向量自身的键覆盖：规则里读一个「本轮没采到」的
     * 维度时拿到 0.0，而不是 map 里缺键导致的 null —— 后者在 PRL 里会在参与算术时直接炸掉整条规则。
     * 这与旧实现直接调 {@code FeatureVector.get()} 的语义一致。</p>
     */
    private Map<String, Object> toRuleInput(FeatureVector fv, AnalysisContext ctx) {
        Map<String, Object> features = new LinkedHashMap<>();
        for (String key : FeatureSchema.keys()) {
            features.put(key, fv.get(key));
        }
        features.putAll(fv.toReportMap());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("features", features);
        input.put("temporal_anomaly", ctx == null ? 0.0 : ctx.temporalAnomaly());
        input.put("click_highly_likely", ctx != null && ctx.click() != null
                && ctx.click().verdict() == ClickIntervalAnalyzer.Verdict.HIGHLY_LIKELY);
        return input;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    // ------------------------------------------------------------------ 内置规则定位

    /**
     * 列出内置规则名。
     *
     * <p><b>为什么不能只靠 {@code getResources("rules")}。</b>那条路要求 jar 里存在一个裸的
     * {@code rules/} 目录条目，而发行加固（POB，与先前的 ProGuard 一样）刻意不写目录条目，
     * 于是同一个目录在开发期（target/classes 真实目录）能枚举到、在发行件里一个都枚举不到 ——
     * 表现是发行版规则全部静默失效，而单测跑在 target/classes 上永远发现不了。所以这里以
     * 「本类所在的代码源」为准：是目录就列目录，是 jar 就遍历 jar 条目，两者都不依赖目录条目。</p>
     */
    private static List<String> builtinRuleNames() {
        Set<String> names = new TreeSet<>(namesFromCodeSource());
        if (names.isEmpty()) {
            // 兜底：非常规类加载器（容器/自定义 loader）下代码源可能取不到，回到资源枚举这条老路
            names.addAll(namesFromResourceUrls());
        }
        return List.copyOf(names);
    }

    /** 从本类所在位置定位规则：目录形态直接列文件，jar 形态遍历条目。 */
    private static Set<String> namesFromCodeSource() {
        Set<String> names = new TreeSet<>();
        try {
            CodeSource codeSource = PrlDetectionEngine.class.getProtectionDomain().getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            if (location == null) {
                return names;
            }
            Path path = Path.of(location.toURI());
            if (Files.isDirectory(path)) {
                collectFromDirectory(names, path.resolve(RULES_DIR));
            } else {
                try (JarFile jar = new JarFile(path.toFile())) {
                    collectFromJar(names, jar);
                }
            }
        } catch (IOException | URISyntaxException e) {
            System.err.println("[PTV-PRL] 定位内置规则失败: " + e.getMessage());
        }
        return names;
    }

    private static Set<String> namesFromResourceUrls() {
        Set<String> names = new TreeSet<>();
        try {
            Enumeration<URL> dirs = PrlDetectionEngine.class.getClassLoader().getResources(RULES_DIR);
            while (dirs.hasMoreElements()) {
                URL dir = dirs.nextElement();
                if ("file".equals(dir.getProtocol())) {
                    collectFromDirectory(names, Path.of(dir.toURI()));
                } else if ("jar".equals(dir.getProtocol())) {
                    try (JarFile jar = ((JarURLConnection) dir.openConnection()).getJarFile()) {
                        collectFromJar(names, jar);
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            System.err.println("[PTV-PRL] 扫描内置规则目录失败: " + e.getMessage());
        }
        return names;
    }

    private static void collectFromDirectory(Set<String> names, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.map(file -> file.getFileName().toString())
                    .filter(PrlDetectionEngine::isRuleFile)
                    .forEach(file -> names.add(stripSuffix(file)));
        }
    }

    /** 遍历 jar 条目按前缀匹配。不要求存在 {@code rules/} 目录条目。 */
    static void collectFromJar(Set<String> names, JarFile jar) {
        List<JarEntry> entries = Collections.list(jar.entries());
        for (JarEntry entry : entries) {
            String entryName = entry.getName();
            if (entryName.startsWith(RULES_PREFIX) && isRuleFile(entryName)
                    && entryName.indexOf('/', RULES_PREFIX.length()) < 0) {
                names.add(stripSuffix(entryName.substring(RULES_PREFIX.length())));
            }
        }
    }

    private static boolean isRuleFile(String fileName) {
        return fileName.endsWith(RULE_SUFFIX) && fileName.length() > RULE_SUFFIX.length();
    }

    private static String stripSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - RULE_SUFFIX.length());
    }

    private static String readBuiltin(String name) {
        try (InputStream in = PrlDetectionEngine.class.getClassLoader()
                .getResourceAsStream(RULES_PREFIX + name + RULE_SUFFIX)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[PTV-PRL] 读取内置规则 " + name + " 失败: " + e.getMessage());
            return null;
        }
    }
}