package com.potatotv.pacc.util;

import java.util.List;

/**
 * v4.5 输入时序统计（纯函数，可单测）。
 * <p>人类鼠标点击间隔近似正态分布（均值约 150ms，标准差约 50ms，变异系数 ≈0.3-0.5）；
 * 鼠标宏点击间隔为固定值或简单周期函数，变异系数趋近 0。</p>
 * <p>输入一组点击间隔（ms），计算均值/标准差/变异系数（CV），并输出宏怀疑分 0-100。</p>
 */
public final class InputTimingAnalyzer {

    private InputTimingAnalyzer() {
    }

    public record MacroVerdict(double meanMs, double stdvMs, double cv, int score, boolean macro) {
    }

    /** 样本过少（&lt;3）无法可靠统计，判定为非宏（score=0）。 */
    public static MacroVerdict analyze(List<Double> intervalsMs, double macroCvThreshold) {
        if (intervalsMs == null || intervalsMs.size() < 3) {
            return new MacroVerdict(0, 0, 0, 0, false);
        }
        double mean = intervalsMs.stream().mapToDouble(Double::doubleValue).summaryStatistics().getAverage();
        if (mean <= 0) {
            return new MacroVerdict(0, 0, 0, 0, false);
        }
        double variance = intervalsMs.stream()
                .mapToDouble(i -> (i - mean) * (i - mean))
                .average().orElse(0.0);
        double stdv = Math.sqrt(variance);
        double cv = stdv / mean;

        boolean macro = cv < macroCvThreshold;
        // 宏：CV 越低（越接近固定周期）分越高，映射 70-100；人类抖动非宏记 0（不影响正常置信）。
        int score = 0;
        if (macro) {
            double ratio = Math.max(0.0, Math.min(1.0, (macroCvThreshold - cv) / macroCvThreshold));
            score = Math.min(70 + (int) Math.round(ratio * 30), 100);
        }
        return new MacroVerdict(mean, stdv, cv, score, macro);
    }
}