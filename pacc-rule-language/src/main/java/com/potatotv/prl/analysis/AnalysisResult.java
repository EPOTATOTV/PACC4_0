package com.potatotv.prl.analysis;

import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.compiler.CompileResult;

import java.util.List;
import java.util.Optional;

/**
 * 静态分析的完整结果（设计文档 §2.14.1 的一张表）。
 *
 * <p>四类分析的载体分别是：类型错误与未使用变量在 {@link #diagnostics()}（类型检查器已经产出，
 * 分析器只是把它们和新增的诊断合并到一处）；空指针、无限循环、安全审计也走 {@code Diagnostic}，
 * 因为它们的最终用途和类型错误一样 —— 管理端标红、发布流程拦截；复杂度与性能预估按规则一条，
 * 单独放在 {@link #metrics()}；冲突是规则两两之间的关系，放在 {@link #conflicts()}。</p>
 *
 * @param compile     编译结果，分析器的输入也是它的副产品
 * @param diagnostics 合并后的诊断列表
 * @param metrics     每条规则一行
 * @param conflicts   规则两两之间的冲突
 */
public record AnalysisResult(CompileResult compile,
                             List<Diagnostic> diagnostics,
                             List<RuleMetrics> metrics,
                             List<RuleConflict> conflicts) {

    public AnalysisResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /** 可以用于发布：无错误、无冲突。警告不拦。 */
    public boolean ok() {
        return errors().isEmpty() && conflicts.isEmpty();
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(diagnostic -> !diagnostic.isError()).toList();
    }

    public Optional<RuleMetrics> metrics(String ruleName) {
        return metrics.stream().filter(metric -> metric.ruleName().equals(ruleName)).findFirst();
    }

    /** 拼成发布流程返回给管理端的一段文本（§2.13.2 的「返回分析报告」）。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic diagnostic : errors()) {
            sb.append("错误 ").append(diagnostic).append('\n');
        }
        for (Diagnostic diagnostic : warnings()) {
            sb.append("警告 ").append(diagnostic).append('\n');
        }
        for (RuleConflict conflict : conflicts) {
            sb.append(PrlAnalyzer.CONFLICT_CODE).append(' ').append(conflict).append('\n');
        }
        for (RuleMetrics metric : metrics) {
            sb.append("规则 ").append(metric.ruleName())
                    .append("：圈复杂度 ").append(metric.cyclomaticComplexity())
                    .append("，嵌套 ").append(metric.maxNestingDepth())
                    .append("，预估 ").append(metric.estimatedInstructions()).append(" 条指令")
                    .append("，").append(metric.estimatedNanos() / 1000).append("µs")
                    .append("，复杂度分 ").append(metric.complexityScore())
                    .append('\n');
        }
        return sb.isEmpty() ? "分析通过" : sb.toString();
    }
}