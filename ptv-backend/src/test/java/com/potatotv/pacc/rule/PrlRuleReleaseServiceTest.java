package com.potatotv.pacc.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.pacc.service.PcuUpdateService;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleStatus;
import com.potatotv.prl.engine.RuleVersion;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 规则发布链路与下发分流的测试（设计文档 §2.13.2 / §3.2.4）。
 *
 * <p>建表语句直接读 {@code V40__prl_rule_versions.sql} 而不是在这里另抄一份：抄一份的话，迁移脚本
 * 改了列而测试还绿着，这个测试就失去了意义。执行在 H2 的 MySQL 兼容模式下（与
 * {@code FlywayMigrationTest} 同一套 URL 参数）。</p>
 *
 * <p>这里跑的是「真存储 + 真引擎」：{@link JdbcRuleVersionStore} 的 SQL、{@link PrlRuleEngine} 的
 * 装载与覆盖都参与，只有网络之外的环节不涉及。断言不只是状态流转，还包括「发布之后求值结果真的变了」
 * 和「灰度比例真的把人群切开」—— 这两条才是这个功能存在的理由。</p>
 */
class PrlRuleReleaseServiceTest {

    private static final String RULE = "account_history";
    private static final String MYSQL_COMPAT_URL_TEMPLATE =
            "jdbc:h2:mem:prlrelease-%s;MODE=MySQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1";

    private JdbcRuleVersionStore store;
    private PrlRuleEngine engine;
    private PrlRuleReleaseService service;

    @BeforeEach
    void setUp() throws Exception {
        String url = String.format(MYSQL_COMPAT_URL_TEMPLATE, UUID.randomUUID());
        createSchema(url);
        store = new JdbcRuleVersionStore(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        engine = new PrlRuleEngine(true, 15.0, store);
        service = new PrlRuleReleaseService(store, engine);
    }

    @Test
    void 从草稿到发布的全过程状态流转正确() {
        RuleVersion draft = service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        assertEquals(RuleStatus.DRAFT, draft.status());
        assertEquals("tester", draft.author());
        assertTrue(service.active(RULE).isEmpty(), "草稿不该被当成生效版本");

        RuleVersion testing = service.startCanary(RULE, "1.0.0", 1);
        assertEquals(RuleStatus.TESTING, testing.status());
        assertEquals(1, service.rolloutPercent(RULE), "灰度比例应当落库");

        RuleVersion active = service.approve(RULE, "1.0.0", "alice");
        assertEquals(RuleStatus.ACTIVE, active.status());
        assertEquals("alice", active.approvedBy());
        assertEquals(100, service.rolloutPercent(RULE), "发布即回到全量");
        assertEquals("1.0.0", service.active(RULE).orElseThrow().version());
    }

    @Test
    void 发布后求值走的是新版本且元数据标为已发布() {
        service.submitDraft(RULE, "2.0.0", source("2.0.0", 10.0), "tester");
        service.approve(RULE, "2.0.0", "alice");

        // 内置规则是 8.0 倍率（信誉 70 → 2.4 分），发布版是 10.0 倍率（3.0 分）
        PrlRuleEngine.Evaluation evaluation = engine.evaluate(ctx(70));
        PrlRuleEngine.RuleHit hit = evaluation.hits().stream()
                .filter(item -> item.id().equals(RULE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("发布后应当命中 " + RULE + "：" + evaluation.hits()));
        assertEquals(3.0, hit.score(), 1e-9, "求值必须用已发布的版本，而不是随包内置版本");

        Map<String, Object> row = rowOf(RULE);
        assertEquals("2.0.0", row.get("version"));
        assertEquals("published", row.get("origin"), "管理端靠 origin 区分规则来自版本库还是随包内置");
    }

    @Test
    void 回滚回到上一个生效版本并把当前版本标记为禁用() {
        service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        service.approve(RULE, "1.0.0", "alice");
        service.submitDraft(RULE, "2.0.0", source("2.0.0", 10.0), "tester");
        service.approve(RULE, "2.0.0", "alice");

        RuleVersion restored = service.rollback(RULE, "1.0.0");
        assertEquals("1.0.0", restored.version());
        assertEquals("1.0.0", service.active(RULE).orElseThrow().version());
        assertEquals(RuleStatus.DISABLED, service.find(RULE, "2.0.0").orElseThrow().status());

        // 回滚后求值回到旧倍率
        assertEquals(2.4, engine.evaluate(ctx(70)).hits().stream()
                .filter(item -> item.id().equals(RULE)).findFirst().orElseThrow().score(), 1e-9);
    }

    @Test
    void 回滚目标与记录不一致时拒绝执行() {
        service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        service.approve(RULE, "1.0.0", "alice");
        // 第一个版本没有上一版可回滚
        assertThrows(PrlException.class, () -> service.rollback(RULE, "1.0.0"));
    }

    @Test
    void 静态分析有错或规则名不符时不入库() {
        assertThrows(PrlException.class,
                () -> service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0) + "\n@@@", "tester"));
        assertThrows(PrlException.class,
                () -> service.submitDraft("other_rule", "1.0.0", source("1.0.0", 8.0), "tester"));
        assertTrue(service.versions(RULE).isEmpty(), "被拒的提交不该在版本库里留下记录");
    }

    @Test
    void 同版本号换内容被拒绝() {
        service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        assertThrows(PrlException.class,
                () -> service.submitDraft(RULE, "1.0.0", source("1.0.0", 9.0), "tester"));
        // 同一份内容重复提交是幂等的，不算冲突
        assertEquals("1.0.0", service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester").version());
    }

    @Test
    void 灰度比例切开人群且灰度外仍拿旧版本() {
        service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        service.approve(RULE, "1.0.0", "alice");
        service.submitDraft(RULE, "2.0.0", source("2.0.0", 10.0), "tester");
        service.startCanary(RULE, "2.0.0", 10);

        String insideCanary = pteidWithBucket(bucket -> bucket < 10);
        String outsideCanary = pteidWithBucket(bucket -> bucket >= 10);

        assertEquals("2.0.0", deliveryOf(insideCanary).version(), "灰度内的设备拿测试版本");
        assertEquals("testing", deliveryOf(insideCanary).status());
        assertEquals("1.0.0", deliveryOf(outsideCanary).version(), "灰度外的设备继续跑生效版本");
        assertEquals("active", deliveryOf(outsideCanary).status());
    }

    @Test
    void 灰度比例为零时不向任何人下发测试版本且全量后覆盖所有人() {
        service.submitDraft(RULE, "2.0.0", source("2.0.0", 10.0), "tester");
        service.startCanary(RULE, "2.0.0", 0);
        // 没有生效版本可退回：这台设备这次拿不到这条规则
        assertTrue(service.deliveriesFor("PTE0000000001").isEmpty(), "0% 灰度且无基线时不该下发");

        service.startCanary(RULE, "2.0.0", 100);
        assertEquals("2.0.0", deliveryOf("PTE0000000001").version(), "100% 灰度覆盖所有设备");
    }

    @Test
    void 试跑返回命中明细() {
        service.submitDraft(RULE, "1.0.0", source("1.0.0", 8.0), "tester");
        service.approve(RULE, "1.0.0", "alice");

        Optional<DetectionResult> hit = service.testRun(RULE, "1.0.0", Map.of("reputation", 70.0));
        assertTrue(hit.isPresent(), "信誉 70 应当命中账号历史规则");
        assertEquals(2.4, ((Number) hit.get().evidence().get("score")).doubleValue(), 1e-9);
        assertFalse(service.testRun(RULE, "1.0.0", Map.of("reputation", 100.0)).isPresent(),
                "信誉满值时不该命中");
    }

    @Test
    void 分析结果给出诊断与复杂度() {
        AnalysisResult result = service.analyze(source("1.0.0", 8.0));
        assertTrue(result.ok(), "内置规则源码应当可以直接发布：" + result.report());
        assertEquals(1, result.metrics().size());
        assertEquals(RULE, result.metrics().get(0).ruleName());
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 找一个桶位落在指定区间的 PTEID。
     *
     * <p>不硬编码 PTEID：分桶口径（SHA-256 前 4 字节）属于 {@link PcuUpdateService}，测试里再算一遍
     * 就等于把同一个假设写两处。这里直接问它。</p>
     */
    private static String pteidWithBucket(java.util.function.IntPredicate predicate) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = String.format("PTE%010d", i);
            if (predicate.test(PcuUpdateService.bucketOf(candidate))) {
                return candidate;
            }
        }
        throw new AssertionError("找不到符合桶位条件的 PTEID");
    }

    private PrlRuleReleaseService.RuleDelivery deliveryOf(String pteid) {
        List<PrlRuleReleaseService.RuleDelivery> deliveries = service.deliveriesFor(pteid);
        assertEquals(1, deliveries.size(), "本次只发布了一条规则：" + deliveries);
        return deliveries.get(0);
    }

    private Map<String, Object> rowOf(String ruleName) {
        return engine.list().stream()
                .filter(row -> ruleName.equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("规则清单里没有 " + ruleName));
    }

    private static Map<String, Object> ctx(int reputation) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("reputation", reputation);
        ctx.put("history_factor", Math.max(0.0, 1.0 - reputation / 100.0));
        return ctx;
    }

    /** 与随包内置规则同构的源码，只把版本号与加分倍率作为变量 —— 这样「发布生效」有可观测的差异。 */
    private static String source(String version, double multiplier) {
        return """
                rule "account_history" {
                    version: "%s"
                    author: "tester"
                    severity: low
                    category: "reputation"
                    description: "账号历史劣迹"
                    enabled: true

                    input {
                        reputation: float
                    }

                    let history_factor = (100.0 - reputation) / 100.0

                    when:
                        history_factor >= 0.3

                    then:
                        emit_alert(
                            type = "account_history",
                            confidence = history_factor,
                            evidence = {
                                "score": history_factor * %s,
                                "reason": "账号历史劣迹加成"
                            }
                        )
                }
                """.formatted(version, multiplier);
    }

    /** 执行 V40 迁移脚本建表；读实际文件而不是抄一份 DDL，避免测试与迁移漂移。 */
    private static void createSchema(String url) throws Exception {
        List<String> statements = new ArrayList<>();
        try (InputStream in = PrlRuleReleaseServiceTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V40__prl_rule_versions.sql")) {
            assertTrue(in != null, "找不到迁移脚本 db/migration/V40__prl_rule_versions.sql");
            StringBuilder text = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().startsWith("--")) {
                    text.append(line).append('\n');
                }
            }
            for (String part : text.toString().split(";")) {
                if (!part.isBlank()) {
                    statements.add(part.trim());
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("建表失败", e);
        }
    }
}