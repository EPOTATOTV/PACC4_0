package com.potatotv.prl.engine;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleManager} 测试，覆盖设计文档 §2.12.2 与验收项 R08 的装载/执行/失败熔断部分。
 *
 * <p>重点断言三件容易写错的事：规则名由引擎补齐（{@code emit_alert} 自己不知道身处哪条规则）、
 * {@code executeAll} 按规则名字典序而不是哈希序返回、以及编译失败时旧规则必须原样保留。</p>
 */
class RuleManagerTest {

    /** 收集日志的宿主；默认 {@code log} 会往 stdout 打，测试里必须换掉才能断言错误上报。 */
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

    /** 无 input、无 when 的规则：恒发出一个告警，用来隔离「装载与执行」这一层。 */
    private static String alertRule(String name, String type) {
        return """
                rule "%s" {
                    then:
                        emit_alert(type = "%s", confidence = 0.5, evidence = {"k": 1})
                }
                """.formatted(name, type);
    }

    private static final String GATED = """
            rule "gated" {
                input {
                    flag: bool
                }
                when:
                    flag
                then:
                    emit_alert(type = "GATED", confidence = 0.5, evidence = {"k": 1})
            }
            """;

    /** 执行时在 INDEX_GET 处越界抛异常，用来驱动失败计数与自动禁用。 */
    private static final String BOOM = """
            rule "boom" {
                input {
                    events: list[int]
                }
                then:
                    let x = events[5]
                    record_evidence("x", x)
            }
            """;

    private static final Map<String, Object> TWO_EVENTS = Map.of("events", List.of(1L, 2L));

    @Test
    void 装载后执行返回告警且引擎补齐规则名() {
        RuleManager manager = new RuleManager(new RecordingHost());
        manager.loadRule("alpha", alertRule("alpha", "ALERT"));

        DetectionResult alert = manager.executeRule("alpha", Map.of()).orElseThrow();
        // §2.4.7：emit_alert 产生的 DetectionResult 里 ruleName 是 null，必须由引擎补上
        assertEquals("alpha", alert.ruleName(), "引擎应在收集告警时补上规则名");
        assertEquals("ALERT", alert.type());
        assertEquals(1, manager.size());
    }

    @Test
    void executeAll按规则名字典序执行() {
        RuleManager manager = new RuleManager(new RecordingHost());
        // 故意打乱装载顺序，断言返回顺序只由规则名决定，与哈希桶顺序无关
        manager.loadRule("zeta", alertRule("zeta", "Z"));
        manager.loadRule("alpha", alertRule("alpha", "A"));
        manager.loadRule("mid", alertRule("mid", "M"));

        List<String> names = manager.executeAll(Map.of()).stream()
                .map(DetectionResult::ruleName)
                .toList();

        assertEquals(List.of("alpha", "mid", "zeta"), names, "executeAll 必须按字典序返回（§2.12.2）");
        assertEquals(List.of("alpha", "mid", "zeta"), manager.ruleNames());
    }

    @Test
    void when为假时不产生告警() {
        RuleManager manager = new RuleManager(new RecordingHost());
        manager.loadRule("gated", GATED);

        assertTrue(manager.executeRule("gated", Map.of("flag", false)).isEmpty(),
                "when 为假时不应返回告警");
        assertTrue(manager.executeAll(Map.of("flag", false)).isEmpty(),
                "when 为假时整批执行也不应产出告警");
        assertEquals("GATED", manager.executeRule("gated", Map.of("flag", true)).orElseThrow().type(),
                "when 为真时必须照常产出告警");
    }

    @Test
    void disable后不再执行() {
        RuleManager manager = new RuleManager(new RecordingHost());
        manager.loadRule("alpha", alertRule("alpha", "ALERT"));
        RuleInstance instance = manager.rule("alpha").orElseThrow();

        instance.disable();

        assertFalse(instance.isEnabled());
        assertTrue(manager.executeRule("alpha", Map.of()).isEmpty(), "禁用后 executeRule 应返回空");
        assertTrue(manager.executeAll(Map.of()).isEmpty(), "禁用后 executeAll 不应包含该规则");

        instance.enable();
        assertEquals("ALERT", manager.executeRule("alpha", Map.of()).orElseThrow().type(),
                "重新启用后应恢复执行");
    }

    @Test
    void 连续失败超过上限后自动禁用并上报宿主() {
        RecordingHost host = new RecordingHost();
        RuleManager manager = new RuleManager(host);
        manager.loadRule("boom", BOOM);

        // 前 MAX_FAILURES 次失败：每次都记一次失败但还没到禁用线（§2.12.2）
        for (int i = 0; i < RuleManager.MAX_FAILURES; i++) {
            assertTrue(manager.executeRule("boom", TWO_EVENTS).isEmpty(), "运行期失败不产出告警");
        }
        RuleInstance instance = manager.rule("boom").orElseThrow();
        assertTrue(instance.isEnabled(), "失败次数还没超过上限时不应禁用");
        assertEquals(RuleManager.MAX_FAILURES, instance.failureCount());

        // 第 11 次失败：recordFailure 返回 11 > MAX_FAILURES(10)，触发自动禁用
        assertTrue(manager.executeRule("boom", TWO_EVENTS).isEmpty());
        assertFalse(instance.isEnabled(), "连续失败 > MAX_FAILURES 后应自动禁用");
        assertEquals(RuleManager.MAX_FAILURES + 1, instance.failureCount(),
                "禁用后不再执行，失败计数应停在 11");
        assertTrue(host.logs.stream().anyMatch(log -> log.startsWith("error|")),
                "自动禁用必须用 error 级别写日志，实际日志=" + host.logs);
    }

    @Test
    void unload移除规则() {
        RuleManager manager = new RuleManager();
        manager.loadRule("alpha", alertRule("alpha", "A"));
        assertEquals(1, manager.size());

        assertTrue(manager.unloadRule("alpha"), "已装载的规则应卸载成功");
        assertEquals(0, manager.size());
        assertFalse(manager.unloadRule("alpha"), "重复卸载应返回 false");
        assertTrue(manager.rule("alpha").isEmpty());
    }

    @Test
    void 编译失败时旧规则原子保留() {
        RuleManager manager = new RuleManager();
        manager.loadRule("keep", alertRule("keep", "V1"));

        String broken = """
                rule "keep" {
                  let a =
                }
                """;
        assertThrows(PrlException.class, () -> manager.loadRule("keep", broken),
                "编译失败必须抛 PrlException");

        // 同名规则是原子替换：没有「先删后加」的窗口，编译失败后旧规则应原样可用
        assertEquals("V1", manager.executeRule("keep", Map.of()).orElseThrow().type(),
                "编译失败不应动到旧规则");
        assertEquals(1, manager.size());

        // 合法的新版本才真正替换
        manager.loadRule("keep", alertRule("keep", "V2"));
        assertEquals("V2", manager.executeRule("keep", Map.of()).orElseThrow().type());
        assertEquals(1, manager.size());
    }

    @Test
    void 多规则文件按名执行正确的规则() {
        RuleManager manager = new RuleManager();
        // 一个 .prl 里两条规则，分别以两个名字装载，执行时必须各取各的入口
        String source = alertRule("one", "ONE") + alertRule("two", "TWO");

        manager.loadRule("one", source);
        manager.loadRule("two", source);

        assertEquals("ONE", manager.executeRule("one", Map.of()).orElseThrow().type());
        assertEquals("TWO", manager.executeRule("two", Map.of()).orElseThrow().type(),
                "文件里有两条规则时不能拿第一条顶替（§2.12.2）");
    }
}