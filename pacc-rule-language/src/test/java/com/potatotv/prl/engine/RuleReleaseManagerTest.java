package com.potatotv.prl.engine;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleReleaseManager} 测试，覆盖设计文档 §2.13.2 与验收项 R09
 * 「版本列表/回滚/灰度正常」。
 *
 * <p>这条流程的价值在于「发布即生效」：{@code approve} 成功的那一刻规则就应装进 {@link RuleManager}
 * 并跑出新行为，所以每个状态迁移用例都额外执行一次规则来确认线上真的换了版本，而不是只看状态字段。</p>
 */
class RuleReleaseManagerTest {

    private static final class RecordingHost implements PrlHostContext {

        final List<String> logs = new ArrayList<>();

        @Override
        public Object callFunction(String name, Object[] args) {
            throw new PrlSecurityException("测试宿主没有注册函数 '" + name + "'");
        }

        @Override
        public Set<String> getAvailableFunctions() {
            return Set.of();
        }

        @Override
        public void log(String level, String message) {
            logs.add(level + "|" + message);
        }
    }

    /** 带 input/when 的规则；alertType 与 confidence 用来区分版本行为。 */
    private static String ruleSource(String name, String alertType, double confidence) {
        return """
                rule "%s" {
                    input {
                        flag: bool
                    }
                    when:
                        flag
                    then:
                        emit_alert(type = "%s", confidence = %s, evidence = {"k": 1})
                }
                """.formatted(name, alertType, confidence);
    }

    private static String alertType(RuleManager manager, String name) {
        return manager.executeRule(name, Map.of("flag", true)).map(DetectionResult::type).orElse(null);
    }

    private static RuleManager newManager() {
        return new RuleManager(new RecordingHost());
    }

    // ------------------------------------------------------------------ 提交草稿

    @Test
    void submitDraft拒绝静态分析有错误的源码() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);

        // require 在沙箱禁用名单里，静态分析会报 PRL-S 错误，发布流程必须把它拦在库外
        String source = """
                rule "evil" {
                    then:
                        require("payload")
                }
                """;

        PrlException error = assertThrows(PrlException.class,
                () -> release.submitDraft("evil", "1.0.0", source, "alice"));
        assertTrue(error.getMessage().contains("静态分析未通过"), error.getMessage());
        assertTrue(release.versions("evil").isEmpty(), "分析不通过的版本不应入库");
    }

    @Test
    void submitDraft拒绝名字对不上的源码() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);

        PrlException error = assertThrows(PrlException.class,
                () -> release.submitDraft("expected", "1.0.0", ruleSource("actual", "A", 0.5), "alice"));
        assertTrue(error.getMessage().contains("名不符实"), error.getMessage());
    }

    @Test
    void submitDraft拒绝同版本号的不同内容() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);
        release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");

        PrlException error = assertThrows(PrlException.class,
                () -> release.submitDraft("r", "1.0.0", ruleSource("r", "B", 0.5), "bob"));
        assertTrue(error.getMessage().contains("已存在且内容不同"), error.getMessage());

        // checksum 只认源码内容：内容完全相同则允许重复提交（幂等）
        assertDoesNotThrow(() -> {
            release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        });
    }

    // ------------------------------------------------------------------ 灰度与审批

    @Test
    void startCanary把草稿转为灰度() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);

        RuleVersion draft = release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        assertEquals(RuleStatus.DRAFT, draft.status());

        RuleVersion testing = release.startCanary("r", "1.0.0");
        assertEquals(RuleStatus.TESTING, testing.status());
        assertTrue(testing.status().isLive(), "灰度属于 live 状态（§2.13.1）");

        assertThrows(PrlException.class, () -> release.startCanary("r", "1.0.0"),
                "非 DRAFT 版本不能再进灰度");
    }

    @Test
    void approve需要审批人并真实装载规则() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);
        release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");

        // §2.13.2：生产环境审批必需，接口里不留「默认通过」的口子
        assertThrows(PrlException.class, () -> release.approve("r", "1.0.0", null));
        assertThrows(PrlException.class, () -> release.approve("r", "1.0.0", "   "));

        RuleVersion active = release.approve("r", "1.0.0", "carol");
        assertEquals(RuleStatus.ACTIVE, active.status());
        assertEquals("carol", active.approvedBy());
        assertEquals("1.0.0", release.active("r").orElseThrow().version());
        // 发布动作即生效动作：规则必须已经装进 RuleManager
        assertTrue(manager.rule("r").isPresent(), "approve 后规则应已装载");
        assertEquals("A", alertType(manager, "r"));
    }

    // ------------------------------------------------------------------ 发布与回滚

    @Test
    void 发布新版本把旧版本降级并记录回滚目标() {
        RuleManager manager = newManager();
        RuleVersionStore store = new InMemoryRuleVersionStore();
        RuleReleaseManager release = new RuleReleaseManager(store, manager);

        release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        release.approve("r", "1.0.0", "carol");

        release.submitDraft("r", "2.0.0", ruleSource("r", "B", 0.5), "alice");
        RuleVersion v2 = release.approve("r", "2.0.0", "carol");

        assertEquals(RuleStatus.ACTIVE, v2.status());
        assertEquals("1.0.0", v2.rollbackTo(), "新版本应回填旧版本作为回滚目标");
        assertEquals(RuleStatus.DEPRECATED, store.find("r", "1.0.0").orElseThrow().status(),
                "被取代的版本应降级为 DEPRECATED");
        assertEquals("2.0.0", release.active("r").orElseThrow().version());
        assertEquals("B", alertType(manager, "r"), "发布后线上应执行新版本");
    }

    @Test
    void rollback恢复旧版本执行并禁用被回滚版本() {
        RuleManager manager = newManager();
        RuleVersionStore store = new InMemoryRuleVersionStore();
        RuleReleaseManager release = new RuleReleaseManager(store, manager);

        release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        release.approve("r", "1.0.0", "carol");
        release.submitDraft("r", "2.0.0", ruleSource("r", "B", 0.5), "alice");
        release.approve("r", "2.0.0", "carol");
        assertEquals("B", alertType(manager, "r"));

        RuleVersion restored = release.rollback("r");

        assertEquals("1.0.0", restored.version());
        assertEquals(RuleStatus.ACTIVE, restored.status());
        assertEquals(RuleStatus.DISABLED, store.find("r", "2.0.0").orElseThrow().status(),
                "被回滚的版本应标为 DISABLED，不能再被发布或回滚选中");
        assertEquals("A", alertType(manager, "r"), "回滚后线上应执行旧版本");
    }

    // ------------------------------------------------------------------ 灰度对比

    @Test
    void compare给出新旧版本的语义对比() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);

        RuleVersion baseline = release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        RuleVersion candidate = release.submitDraft("r", "2.0.0", ruleSource("r", "A", 0.9), "alice");
        RuleVersion twin = release.submitDraft("r", "3.0.0", ruleSource("r", "A", 0.5), "alice");

        CanaryComparison changed = release.compare(baseline, candidate, Map.of("flag", true));
        assertFalse(changed.agrees(), "置信度不同应判为结论不一致");
        assertEquals(0.4, changed.confidenceDelta(), 1e-9, "新版本置信度应比旧版本高 0.4");
        assertFalse(changed.report().isBlank(), "对比应产出一段可展示的报告");

        CanaryComparison same = release.compare(baseline, twin, Map.of("flag", true));
        assertTrue(same.agrees(), "行为完全相同的版本应判为一致");
        assertEquals(0.0, same.confidenceDelta(), 1e-9);

        // 未触发时两边都无告警，也属于一致
        CanaryComparison silent = release.compare(baseline, candidate, Map.of("flag", false));
        assertTrue(silent.agrees(), "两边都无告警视为一致");
    }

    // ------------------------------------------------------------------ 查询

    @Test
    void versions列出全部版本且active指向生效版本() {
        RuleManager manager = newManager();
        RuleReleaseManager release = new RuleReleaseManager(new InMemoryRuleVersionStore(), manager);

        release.submitDraft("r", "1.0.0", ruleSource("r", "A", 0.5), "alice");
        release.approve("r", "1.0.0", "carol");
        release.submitDraft("r", "2.0.0", ruleSource("r", "B", 0.5), "alice");

        List<String> listed = release.versions("r").stream().map(RuleVersion::version).toList();
        assertEquals(2, listed.size(), "两个版本都应列出：" + listed);
        assertTrue(listed.containsAll(List.of("1.0.0", "2.0.0")), listed.toString());
        assertEquals("1.0.0", release.active("r").orElseThrow().version(),
                "尚未发布的 2.0.0 不应是 ACTIVE");
        assertTrue(release.versions("unknown").isEmpty(), "未知规则应返回空列表");
    }
}