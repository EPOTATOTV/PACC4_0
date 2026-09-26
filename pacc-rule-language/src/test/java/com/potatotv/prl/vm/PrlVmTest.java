package com.potatotv.prl.vm;

import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.bytecode.BytecodeCompiler;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.check.TypeCheckResult;
import com.potatotv.prl.check.TypeChecker;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.ir.IrGenerator;
import com.potatotv.prl.ir.IrProgram;
import com.potatotv.prl.parser.Parser;
import com.potatotv.prl.runtime.PrlExecutionException;
import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字节码解释器测试（设计文档 §2.9.1、§2.11.1 L2）。
 *
 * <p>全部用例都走完整流水线：源码 → 词法 → 语法 → 类型检查 → IR → 字节码 → VM。
 * 只测 VM 而不测编译产物是没有意义的 —— 寄存器编号、操作数宽度、跳转偏移这些约定由两侧共同
 * 持有，单独一侧自洽不代表能跑通。</p>
 *
 * <p>输入对象用 {@link Map} 表示事件（宿主给的结构体走 {@code PrlHostContext.getMember} 的
 * Map 分支），只有需要方法调用的对象（{@code PlayerContext.is_trusted}）才实现
 * {@link PrlHostObject}。</p>
 */
class PrlVmTest {

    private static final Path EXAMPLES = Path.of("examples");

    // ------------------------------------------------------------------ 桩

    /** 记录副作用的宿主；函数白名单为空，规则里的调用一律落到标准库。 */
    private static final class RecordingHost implements PrlHostContext {

        final List<DetectionResult> alerts = new ArrayList<>();
        final Map<String, Object> evidence = new LinkedHashMap<>();
        final List<String> redscreens = new ArrayList<>();

        @Override
        public Object callFunction(String name, Object[] args) {
            throw new PrlSecurityException("测试宿主没有注册函数 '" + name + "'");
        }

        @Override
        public Set<String> getAvailableFunctions() {
            return Set.of();
        }

        @Override
        public void acceptAlert(DetectionResult alert) {
            alerts.add(alert);
        }

        @Override
        public void recordEvidence(String key, Object value) {
            evidence.put(key, value);
        }

        @Override
        public void triggerRedScreen(String reason) {
            redscreens.add(reason);
        }
    }

    /** {@code PlayerContext} 的测试替身，只为 {@code is_trusted()} 这个方法而存在。 */
    private static final class FakePlayer implements PrlHostObject {

        private final boolean trusted;

        FakePlayer(boolean trusted) {
            this.trusted = trusted;
        }

        @Override
        public Object getMember(String member) {
            return switch (member) {
                case "id" -> "uuid-0001";
                case "name" -> "Tester";
                case "reputation" -> 100L;
                case "platform" -> "windows";
                default -> throw new PrlSecurityException("PlayerContext 没有成员 '" + member + "'");
            };
        }

        @Override
        public Object callMethod(String method, Object[] args) {
            if (!"is_trusted".equals(method)) {
                throw new PrlSecurityException("PlayerContext 没有方法 '" + method + "'");
            }
            return trusted;
        }
    }

    // ------------------------------------------------------------------ 助手

    private static PrlBytecode compile(String source) {
        RuleFile file = Parser.parseSource(source);
        TypeCheckResult types = new TypeChecker().check(file);
        assertTrue(types.ok(), () -> "类型检查失败：" + types.errors());
        IrProgram ir = IrGenerator.generate(file, types);
        return BytecodeCompiler.compile(file, ir);
    }

    private static Object run(String source, PrlHostContext host, Map<String, Object> input) {
        return new PrlVm(host).execute(compile(source), input);
    }

    /** 造一个 {@code AttackEvent}（宿主侧用 map 表示结构体）。 */
    private static Map<Object, Object> attackEvent(long timeMs, boolean targetChanged, double damage) {
        Map<Object, Object> event = new LinkedHashMap<>();
        event.put("time_ms", timeMs);
        event.put("target_changed", targetChanged);
        event.put("damage", damage);
        return event;
    }

    private static String failureMessage(Throwable error) {
        return error.getMessage();
    }

    // ------------------------------------------------------------------ 算术与插值

    private static final String ARITH = """
            rule "arith" {
                input {
                    events: list[int]
                    threshold: int
                }
                let total = sum(events)
                let doubled = total * 2

                when:
                    total > threshold

                then:
                    emit_alert(
                        type = "ARITH",
                        confidence = 0.75,
                        evidence = {
                            "total": total,
                            "doubled": doubled,
                            "summary": f"total={total} doubled={doubled}"
                        }
                    )
            }
            """;

    @Test
    void arithmeticAndStringInterpolation() {
        RecordingHost host = new RecordingHost();
        Object result = run(ARITH, host, Map.of("events", List.of(10L, 20L), "threshold", 5L));

        DetectionResult alert = assertInstanceOf(DetectionResult.class, result);
        assertEquals("ARITH", alert.type());
        assertEquals(0.75, alert.confidence(), 1e-9);
        assertEquals(30L, alert.evidence().get("total"));
        assertEquals(60L, alert.evidence().get("doubled"));
        assertEquals("total=30 doubled=60", alert.evidence().get("summary"));
        assertEquals(List.of(alert), host.alerts);
    }

    @Test
    void whenFalseSkipsThenBlockAndReturnsNull() {
        RecordingHost host = new RecordingHost();
        Object result = run(ARITH, host, Map.of("events", List.of(10L, 20L), "threshold", 100L));

        assertNull(result);
        assertTrue(host.alerts.isEmpty());
    }

    // ------------------------------------------------------------------ 具名实参

    @Test
    void namedArgumentsAreRemappedToParameterOrder() {
        String source = """
                rule "named" {
                    input {
                        c: float
                    }
                    then:
                        emit_alert(evidence = {"k": 1}, confidence = c, type = "NAMED")
                }
                """;
        RecordingHost host = new RecordingHost();
        Object result = run(source, host, Map.of("c", 0.4));

        DetectionResult alert = assertInstanceOf(DetectionResult.class, result);
        assertEquals("NAMED", alert.type());
        assertEquals(0.4, alert.confidence(), 1e-9);
        assertEquals(1L, alert.evidence().get("k"));
    }

    // ------------------------------------------------------------------ lambda 与标准库

    @Test
    void lambdaCapturesOuterVariableAndCallsStdlib() {
        String source = """
                rule "lambda" {
                    input {
                        events: list[AttackEvent]
                        damage_limit: float
                    }
                    let big = filter(events, e -> e.damage > damage_limit)
                    let damages = map(big, e -> e.damage)

                    when:
                        count(big) > 0

                    then:
                        emit_alert("LAMBDA", first(damages), {"count": count(big), "first": first(damages)})
                }
                """;
        List<Object> events = List.of(
                attackEvent(0L, false, 80.0),
                attackEvent(10L, false, 60.0),
                attackEvent(20L, false, 10.0));

        RecordingHost host = new RecordingHost();
        Object result = run(source, host, Map.of("events", events, "damage_limit", 50.0));

        DetectionResult alert = assertInstanceOf(DetectionResult.class, result);
        assertEquals(80.0, alert.confidence(), 1e-9);
        assertEquals(2L, alert.evidence().get("count"));
        assertEquals(80.0, alert.evidence().get("first"));
    }

    // ------------------------------------------------------------------ 循环

    @Test
    void forLoopWithCompoundAssign() {
        String source = """
                rule "forloop" {
                    input {
                        values: list[int]
                    }
                    let total = 0

                    when:
                        count(values) > 0

                    then:
                        for v in values:
                            total += v
                        end
                        record_evidence("total", total)
                }
                """;
        RecordingHost host = new RecordingHost();
        Object result = run(source, host, Map.of("values", List.of(1L, 2L, 3L, 4L)));

        assertNull(result);
        assertEquals(10L, host.evidence.get("total"));
    }

    @Test
    void whileLoopAccumulatesUntilConditionFails() {
        String source = """
                rule "whileloop" {
                    input {
                        limit: int
                    }
                    let i = 0
                    let acc = 0

                    then:
                        while i < limit:
                            acc += i
                            i += 1
                        end
                        record_evidence("acc", acc)
                }
                """;
        RecordingHost host = new RecordingHost();
        run(source, host, Map.of("limit", 5L));

        assertEquals(10L, host.evidence.get("acc"));
    }

    // ------------------------------------------------------------------ 空合并与下标

    @Test
    void coalesceFallsBackWhenMapKeyIsMissing() {
        String source = """
                rule "coalesce" {
                    input {
                        flags: map[string, bool]
                    }

                    when:
                        flags["force"] ?? false

                    then:
                        emit_alert("COALESCE", 1.0, {"forced": true})
                }
                """;
        RecordingHost hit = new RecordingHost();
        Object result = run(source, hit, Map.of("flags", Map.of("force", Boolean.TRUE)));
        assertEquals("COALESCE", assertInstanceOf(DetectionResult.class, result).type());

        RecordingHost miss = new RecordingHost();
        assertNull(run(source, miss, Map.of("flags", Map.of())));
        assertTrue(miss.alerts.isEmpty());
    }

    @Test
    void indexOutOfBoundsIsReported() {
        String source = """
                rule "oob" {
                    input {
                        values: list[int]
                    }
                    then:
                        record_evidence("v", values[10])
                }
                """;
        PrlExecutionException error = assertThrows(PrlExecutionException.class,
                () -> run(source, new RecordingHost(), Map.of("values", List.of(1L))));
        assertTrue(failureMessage(error).contains("下标 10 越界"), failureMessage(error));
    }

    // ------------------------------------------------------------------ 沙箱限制

    @Test
    void instructionLimitStopsInfiniteLoop() {
        String source = """
                rule "spin" {
                    input {
                        n: int
                    }
                    let i = 0
                    then:
                        while i < n:
                            i += 1
                        end
                }
                """;
        PrlExecutionException error = assertThrows(PrlExecutionException.class,
                () -> run(source, new RecordingHost(), Map.of("n", 1_000_000L)));
        assertTrue(failureMessage(error).contains("指令数超过"), failureMessage(error));
    }

    @Test
    void rangeSizeLimitIsEnforced() {
        String source = """
                rule "range" {
                    input {
                        n: int
                    }
                    let values = 0..n
                    then:
                        record_evidence("len", count(values))
                }
                """;
        PrlExecutionException error = assertThrows(PrlExecutionException.class,
                () -> run(source, new RecordingHost(), Map.of("n", 20_000L)));
        assertTrue(failureMessage(error).contains("超过上限"), failureMessage(error));
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    void killAuraExampleRunsEndToEnd() throws IOException {
        String source = Files.readString(EXAMPLES.resolve("kill_aura.prl"));

        // 20 次攻击、每次间隔 50ms：攻击速率 20 次/秒、命中率 1.0、转火速率 20 次/秒，
        // 间隔标准差 0 且 cps 20 判为非人类点击模式，玩家未受信任 —— 五条判据全部成立。
        List<Object> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(attackEvent(i * 50L, true, 6.0));
        }

        RecordingHost host = new RecordingHost();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("player", new FakePlayer(false));
        input.put("attack_events", events);
        input.put("system_state", null);

        Object result = new PrlVm(host).execute(compile(source), input);

        DetectionResult alert = assertInstanceOf(DetectionResult.class, result);
        assertEquals("KILL_AURA", alert.type());
        assertEquals(0.92, alert.confidence(), 1e-9);
        assertEquals(20.0, ((Number) alert.evidence().get("attack_rate")).doubleValue(), 1e-9);
        assertEquals(1.0, ((Number) alert.evidence().get("hit_accuracy")).doubleValue(), 1e-9);
        assertEquals(20.0, ((Number) alert.evidence().get("target_switch_rate")).doubleValue(), 1e-9);
        // click_pattern.to_json() 走宿主对象的方法调用，结果应当是一段 JSON 文本
        String pattern = PrlValues.asString(alert.evidence().get("click_pattern"));
        assertTrue(pattern.contains("\"cps\":20.0"), pattern);

        // record_evidence 不该把 emit_alert 的结果从返回槽里挤掉，同时本身要生效
        assertEquals(1, host.evidence.size());
        assertEquals(20, PrlValues.asList(host.evidence.get("attack_timeline")).size());
        assertEquals(List.of("kill_aura_detected"), host.redscreens);
    }
}