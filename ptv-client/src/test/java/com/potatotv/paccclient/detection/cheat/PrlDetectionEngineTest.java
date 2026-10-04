package com.potatotv.paccclient.detection.cheat;

import static com.potatotv.paccclient.detection.cheat.CheatRuleCases.Case;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.prl.PrlException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 端侧 PRL 规则引擎测试（设计文档 §3.2 的验收：硬编码规则全部迁移为 PRL，且行为不变）。
 *
 * <p>期望值不是手算的：先用迁移前的 15 个 Java 规则类跑一遍 {@link CheatRuleCases} 的用例集，
 * 把「命中类型 / 分值 / 严重度 / 证据键顺序」录进 {@code src/test/resources/cheat-rule-golden.txt}，
 * 再让 PRL 版本逐条比对。端侧规则的分值是逐项加分算出来的，靠读代码推极容易差几分或漏一个
 * 条件分支下的证据键 —— 这份金标是唯一能证明「迁移没改变判定结果」的东西。</p>
 *
 * <p>迁移完成后 Java 规则类已删除，所以金标以文件形式钉住；后续任何人改规则，
 * 只要分值、严重度或证据键变了，这个测试就会红。</p>
 */
class PrlDetectionEngineTest {

    private static final int EXPECTED_RULE_COUNT = 36;

    private PrlDetectionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PrlDetectionEngine();
        assertEquals(EXPECTED_RULE_COUNT, engine.loadBuiltin(), "内置规则应当全部装载成功");
    }

    @Test
    void 十五种作弊类型的规则名齐全() {
        List<String> names = engine.ruleNames();
        assertEquals(EXPECTED_RULE_COUNT, names.size());
        for (CheatType type : CheatType.values()) {
            if (type == CheatType.AUTOCLICKER || type == CheatType.KILLAURA || type == CheatType.FLY
                    || type == CheatType.SPEED || type == CheatType.REACH) {
                // 这 5 种由 L0 硬阈值判定，不属于规则文件覆盖的范围
                continue;
            }
            assertTrue(names.contains(type.code()), "缺少规则：" + type.code());
        }
    }

    @Test
    void 与迁移前的Java规则金标逐条一致() throws IOException {
        Map<String, List<String>> golden = readGolden();
        for (Case testCase : CheatRuleCases.all()) {
            List<String> expected = golden.get(testCase.name());
            assertTrue(expected != null, "金标缺少用例：" + testCase.name());
            List<String> actual = render(engine.evaluate(testCase.fv(), testCase.context()));
            assertEquals(expected, actual, "用例 [" + testCase.name() + "] 的判定结果与迁移前不一致");
        }
        assertEquals(CheatRuleCases.all().size(), golden.size(), "金标用例数与用例集不一致");
    }

    @Test
    void 分值最高的一条由evaluateTop给出() {
        for (Case testCase : CheatRuleCases.all()) {
            List<CheatFinding> hits = engine.evaluate(testCase.fv(), testCase.context());
            var top = engine.evaluateTop(testCase.fv(), testCase.context());
            assertEquals(!hits.isEmpty(), top.isPresent(), "用例 [" + testCase.name() + "] 顶部命中存在性不一致");
            hits.stream().findFirst().ifPresent(first ->
                    assertEquals(first, top.orElseThrow(), "用例 [" + testCase.name() + "] 顶部命中不一致"));
        }
    }

    @Test
    void 全零输入不产生任何命中() {
        CheatRuleCases.Case blank = CheatRuleCases.all().stream()
                .filter(c -> "全零向量".equals(c.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(engine.evaluate(blank.fv(), blank.context()).isEmpty());
        assertTrue(engine.evaluate(null, blank.context()).isEmpty());
        assertTrue(engine.evaluate(blank.fv(), null).isEmpty());
    }

    @Test
    void 编译失败的规则保留旧版本() {
        Case testCase = CheatRuleCases.all().stream()
                .filter(c -> "fasteat_极快且规律".equals(c.name()))
                .findFirst()
                .orElseThrow();
        List<String> before = render(engine.evaluate(testCase.fv(), testCase.context()));
        assertFalse(before.isEmpty(), "前置条件：该用例本应命中 fasteat");

        // 语法错误 / 类型错误都不该把已经装载的规则改掉
        assertThrows(PrlException.class, () -> engine.updateRule("fasteat", "rule \"fasteat\" { when: 1 end"));
        assertThrows(PrlException.class, () -> engine.updateRule("fasteat", """
                rule "fasteat" {
                    input {
                        features: map[string, float]
                        temporal_anomaly: float
                        click_highly_likely: bool
                    }
                    when:
                        features["feature_fasteat_duration_mean"] > "不是数字"
                    then:
                        emit_alert(type = "fasteat", confidence = 0.5, evidence = {"score": 50.0, "keys": []})
                }
                """));
        assertEquals(before, render(engine.evaluate(testCase.fv(), testCase.context())),
                "编译失败后旧规则必须原样保留");
        assertEquals(EXPECTED_RULE_COUNT, engine.size());
    }

    @Test
    void 热更新后新版本立即生效() {
        engine.updateRule("fasteat", """
                rule "fasteat" {
                    version: "9.9.9"
                    severity: medium
                    input {
                        features: map[string, float]
                        temporal_anomaly: float
                        click_highly_likely: bool
                    }
                    when:
                        features["feature_fasteat_duration_mean"] > 0.0
                    then:
                        emit_alert(
                            type = "fasteat",
                            confidence = 0.7,
                            evidence = {
                                "score": 70.0,
                                "keys": ["feature_fasteat_duration_mean"]
                            }
                        )
                }
                """);
        CheatRuleCases.Case slow = CheatRuleCases.all().stream()
                .filter(c -> "fasteat_正常时长".equals(c.name()))
                .findFirst()
                .orElseThrow();
        // 旧版本对这个用例不命中（时长 1500 视为正常），新版本无条件命中
        assertEquals(List.of("fasteat\t70\thigh\tfeature_fasteat_duration_mean"),
                render(engine.evaluate(slow.fv(), slow.context())));
    }

    @Test
    void 未知作弊类型的规则在装载期就被拒绝() {
        // 规则名即作弊类型 code。装一条名字认不出的规则，只会产出永远被丢弃的告警，
        // 所以这里在装载期就拒掉，而不是等求值时才发现。
        assertThrows(IllegalArgumentException.class,
                () -> engine.updateRule("not_a_cheat_type", """
                        rule "not_a_cheat_type" {
                            input {
                                features: map[string, float]
                                temporal_anomaly: float
                                click_highly_likely: bool
                            }
                            when:
                                true
                            then:
                                emit_alert(
                                    type = "not_a_cheat_type",
                                    confidence = 0.9,
                                    evidence = {
                                        "score": 90.0,
                                        "keys": []
                                    }
                                )
                        }
                        """));
        assertEquals(EXPECTED_RULE_COUNT, engine.size(), "被拒绝的规则不应留下任何痕迹");
        assertTrue(engine.evaluate(new FeatureVector(), null).isEmpty(),
                "未知 type 的告警必须被丢弃");
    }

    @Test
    void 发行版jar没有目录条目时仍能枚举到规则() throws IOException {
        // 这是真实踩过的坑：发行加固（POB，与先前的 ProGuard 一样）不写 rules/ 目录条目，
        // 靠 getResources("rules") 枚举的写法
        // 在发行件上一条规则都找不到（开发期跑 target/classes 完全正常，所以单测发现不了）。
        // 这里造一个「只有条目、没有目录节点」的 jar，钉住「不依赖目录条目」这个前提。
        Path jarPath = Files.createTempFile("prl-rules-", ".jar");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
                for (String name : List.of("fasteat", "scaffold", "blink")) {
                    zip.putNextEntry(new ZipEntry("rules/" + name + ".prl"));
                    zip.write("rule \"x\" { then: return end".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                // 干扰项：同前缀的其它资源与子目录，都不该被当成规则
                zip.putNextEntry(new ZipEntry("rules/README.md"));
                zip.write("hi".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("rules/nested/deep.prl"));
                zip.write("x".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Set<String> scanned = new TreeSet<>();
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                assertFalse(hasDirectoryEntry(jar, "rules/"), "构造的 jar 不应含 rules/ 目录条目");
                PrlDetectionEngine.collectFromJar(scanned, jar);
            }
            assertEquals(List.of("blink", "fasteat", "scaffold"), List.copyOf(scanned));
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    private static boolean hasDirectoryEntry(JarFile jar, String name) {
        return jar.stream().anyMatch(entry -> entry.getName().equals(name) && entry.isDirectory());
    }

    /**
     * 非整数分值的取整口径必须与迁移前一致（截断，不是四舍五入）。
     *
     * <p>这几条期望值是从被删除的 Java 规则公式直接推的，不是机器生成的金标：四条连续量规则的
     * 分值都是 {@code (int) Math.min(...)} 强转。四舍五入会在这些输入上多给 1 分，而 69 与 70
     * 正好跨过 severity 的 70 分分档，属于可观测的行为变化。</p>
     */
    @Test
    void 非整数分值按截断取整而不是四舍五入() {
        record TruncCase(String name, Map<String, Double> features, int score, String severity) {
        }
        List<TruncCase> cases = List.of(
                // fastplace: (int) min(85, 40 + (5.74-2)*8) = (int) 69.92 = 69（四舍五入会得到 70/high）
                new TruncCase("fastplace", Map.of("feature_fastplace_block_per_sec", 5.74), 69, "medium"),
                // cheststealer: (int) min(85, 40 + (11.4-5)*4) = (int) 65.6 = 65
                new TruncCase("cheststealer", Map.of("feature_cheststealer_items_per_sec", 11.4), 65, "medium"),
                // invmanager: (int) min(85, 40 + (13.3-8)*4) = (int) 61.2 = 61
                new TruncCase("invmanager", Map.of("feature_invmanager_ops_per_sec", 13.3), 61, "medium"),
                // fasteat: (int) min(75, 45 + (1400-950)/100) = (int) 49.5 = 49
                new TruncCase("fasteat", Map.of("feature_fasteat_duration_mean", 950.0), 49, "medium"));

        for (TruncCase testCase : cases) {
            FeatureVector fv = new FeatureVector();
            testCase.features().forEach(fv::put);
            List<CheatFinding> hits = engine.evaluate(fv, AnalysisContext.empty());
            CheatFinding expected = hits.stream()
                    .filter(hit -> hit.type().code().equals(testCase.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(testCase.name() + " 未命中：" + hits));
            assertEquals(testCase.score(), expected.score(), testCase.name() + " 的分值取整口径不一致");
            assertEquals(testCase.severity(), expected.severity(), testCase.name() + " 的严重度分档不一致");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 与金标同构的一行：类型 / 分值 / 严重度 / 证据键（逗号连接）。 */
    private static List<String> render(List<CheatFinding> hits) {
        List<String> lines = new ArrayList<>(hits.size());
        for (CheatFinding hit : hits) {
            lines.add(hit.type().code() + "\t" + hit.score() + "\t" + hit.severity()
                    + "\t" + String.join(",", hit.evidenceKeys()));
        }
        return lines;
    }

    private static Map<String, List<String>> readGolden() throws IOException {
        Map<String, List<String>> golden = new LinkedHashMap<>();
        try (InputStream in = PrlDetectionEngineTest.class.getResourceAsStream("/cheat-rule-golden.txt")) {
            assertTrue(in != null, "缺少金标文件 cheat-rule-golden.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                String caseName = parts[0];
                List<String> findings = golden.computeIfAbsent(caseName, key -> new ArrayList<>());
                if (parts.length == 2 && "-".equals(parts[1])) {
                    continue;
                }
                findings.add(parts[1] + "\t" + parts[2] + "\t" + parts[3] + "\t" + parts[4]);
            }
        }
        return golden;
    }
}