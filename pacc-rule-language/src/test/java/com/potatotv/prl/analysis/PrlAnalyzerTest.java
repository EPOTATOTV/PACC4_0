package com.potatotv.prl.analysis;

import com.potatotv.prl.check.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrlAnalyzer} 测试，覆盖设计文档 §2.14 与验收项 R10
 * 「静态分析：可检测类型错误/未使用变量/规则冲突」。
 *
 * <p>分析结果把错误和警告混在一个诊断列表里（它们最终用途相同：管理端标红、发布流程取舍），
 * 所以断言前必须用 {@code errors()}/{@code warnings()} 分开筛。诊断码一律引用
 * {@link PrlAnalyzer} 的常量，避免字面量和实现漂移。</p>
 */
class PrlAnalyzerTest {

    private static final PrlAnalyzer ANALYZER = new PrlAnalyzer();

    private static List<Diagnostic> errorsWithCode(AnalysisResult result, String code) {
        return result.errors().stream().filter(diagnostic -> code.equals(diagnostic.code())).toList();
    }

    private static List<Diagnostic> warningsWithCode(AnalysisResult result, String code) {
        return result.warnings().stream().filter(diagnostic -> code.equals(diagnostic.code())).toList();
    }

    @Test
    void 可证明的无限循环报错误() {
        AnalysisResult result = ANALYZER.analyze("""
                rule "spin" {
                    then:
                        while true:
                            record_evidence("tick", 1)
                        end
                }
                """);

        assertFalse(errorsWithCode(result, PrlAnalyzer.INFINITE_LOOP_CODE).isEmpty(),
                "while true 且循环体没有 return 应判为无限循环，实际=" + result.report());
    }

    @Test
    void 潜在空指针给警告且被兜住时不报() {
        AnalysisResult risky = ANALYZER.analyze("""
                rule "risky" {
                    input {
                        events: list[AttackEvent]
                    }
                    let first_damage = first(events).damage
                    then:
                        record_evidence("d", first_damage)
                }
                """);
        // first(...) 在空集合上返回 null，直接取其成员是 §2.14 认定的空指针来源
        assertFalse(warningsWithCode(risky, PrlAnalyzer.NULL_RISK_CODE).isEmpty(),
                "first(...) 后直接取成员应给 PRL-N 警告，实际=" + risky.report());

        AnalysisResult guarded = ANALYZER.analyze("""
                rule "guarded" {
                    input {
                        events: list[AttackEvent]
                    }
                    let first_damage = first(events).damage ?? 0.0
                    then:
                        record_evidence("d", first_damage)
                }
                """);
        // 对照组：左值被 ?? 兜住后不应再报空指针
        assertTrue(warningsWithCode(guarded, PrlAnalyzer.NULL_RISK_CODE).isEmpty(),
                "?? 兜住左值后不应再报空指针，实际=" + guarded.report());
    }

    @Test
    void 沙箱禁用函数与成员报安全错误() {
        AnalysisResult result = ANALYZER.analyze("""
                rule "escape" {
                    input {
                        player: PlayerContext
                    }
                    then:
                        http_get("http://example.com")
                        let runtime_handle = player.runtime
                }
                """);

        assertFalse(errorsWithCode(result, PrlAnalyzer.SECURITY_CODE).isEmpty(),
                "http_get 与 runtime 都在沙箱禁用名单里（§2.18.2），实际=" + result.report());
    }

    @Test
    void 条件相同但告警类型不同判为冲突() {
        AnalysisResult result = ANALYZER.analyze(ruleWithCondition("high_suspect", "SUSPICIOUS")
                + ruleWithCondition("high_normal", "NORMAL"));

        assertEquals(1, result.conflicts().size(), "应只产出一条冲突，实际=" + result.report());
        RuleConflict conflict = result.conflicts().get(0);
        assertEquals(Set.of("high_suspect", "high_normal"),
                Set.of(conflict.ruleA(), conflict.ruleB()));
        assertTrue(conflict.reason().contains("告警类型不同"), conflict.reason());
    }

    @Test
    void 条件与类型都相同判为重复告警() {
        AnalysisResult result = ANALYZER.analyze(ruleWithCondition("dup_a", "SUSPICIOUS")
                + ruleWithCondition("dup_b", "SUSPICIOUS"));

        assertEquals(1, result.conflicts().size(), result.report());
        // reason 只描述「条件与类型都相同」，处理建议才是「会重复告警，删掉一条」
        assertTrue(result.conflicts().get(0).suggestion().contains("重复告警"),
                result.conflicts().get(0).suggestion());
    }

    @Test
    void 未使用变量在分析结果里给警告() {
        AnalysisResult result = ANALYZER.analyze("""
                rule "unused" {
                    then:
                        let dead = 1
                }
                """);

        assertTrue(result.errors().isEmpty(), "只是一个未使用变量，不应报错：" + result.report());
        // 类型检查器的诊断被原样合并进来，分析器不重算一遍（§2.14.1）
        assertTrue(result.warnings().stream()
                        .anyMatch(diagnostic -> diagnostic.message().contains("声明后没有被读取")),
                "未使用变量应作为警告出现在分析结果里，实际=" + result.warnings());
    }

    @Test
    void 复杂度与性能预估非平凡且报告非空() {
        AnalysisResult result = ANALYZER.analyze("""
                rule "heavy" {
                    input {
                        xs: list[int]
                        threshold: int
                    }
                    let total = sum(xs)
                    when:
                        total > threshold
                    then:
                        if total > 100:
                            record_evidence("big", total)
                        end
                        for v in xs:
                            record_evidence("v", v)
                        end
                }
                """);

        RuleMetrics metrics = result.metrics("heavy").orElseThrow();
        // when + if + for 各算一个判定点，圈复杂度必须随之增长
        assertTrue(metrics.cyclomaticComplexity() >= 2, "圈复杂度应计入判定点：" + metrics);
        assertTrue(metrics.estimatedInstructions() > 0, "指令数应大于 0：" + metrics);
        assertTrue(metrics.complexityScore() >= 1, "复杂度分应大于 0：" + metrics);
        assertFalse(result.report().isBlank(), "report() 应给出一段可展示的分析报告");
    }

    /** 造一条恒用同一条件、只换告警类型的规则，用来构造冲突/重复告警。 */
    private static String ruleWithCondition(String name, String alertType) {
        return """
                rule "%s" {
                    input {
                        rate: float
                    }
                    when:
                        rate > 8.0
                    then:
                        emit_alert(type = "%s", confidence = 0.5, evidence = {"k": 1})
                }
                """.formatted(name, alertType);
    }
}