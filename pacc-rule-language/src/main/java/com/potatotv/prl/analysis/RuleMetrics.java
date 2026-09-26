package com.potatotv.prl.analysis;

/**
 * 单条规则的复杂度与性能预估（设计文档 §2.14.1 的「复杂度分析」「性能预估」）。
 *
 * <p>都是静态估算，不是实测值：{@link #estimatedNanos} 由指令数乘 10ns 得到，这个系数是从 §2.17
 * 给的「字节码解释器 ~0.2–1ms/规则」除以 §2.11.1 的 10 万条指令预算反推的。用途是筛出「这条规则写得太重」
 * 的候选，真要知道耗时得看 {@code Profiler} 的实测数据。</p>
 *
 * @param cyclomaticComplexity   圈复杂度：1 + 判定点数（if/for/while/when/AND/OR/三元）
 * @param maxNestingDepth        控制流最大嵌套深度
 * @param estimatedInstructions  AST 节点数，近似字节码条数
 * @param estimatedNanos         单次执行时间预估（纳秒）
 * @param estimatedHeapBytes     堆占用预估（字节）
 * @param complexityScore        0~100 的合成分，越高越难维护
 */
public record RuleMetrics(String ruleName,
                          int cyclomaticComplexity,
                          int maxNestingDepth,
                          int estimatedInstructions,
                          long estimatedNanos,
                          long estimatedHeapBytes,
                          int complexityScore) {

    /** 分数达到这个值就值得拆规则。 */
    public static final int HIGH_COMPLEXITY = 40;

    public boolean complex() {
        return complexityScore >= HIGH_COMPLEXITY;
    }
}