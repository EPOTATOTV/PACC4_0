package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer;
import com.potatotv.paccclient.detection.analysis.TrajectoryAnalyzer;

import java.util.List;

/**
 * 行为分析上下文：把时序/轨迹分析与端侧 AI 推理结果打包，供规则引擎
 * （{@code detection.cheat.CheatRule}）与端云分层判定复用，避免各规则重复计算。
 *
 * @param click           点击间隔分布分析（可为 {@code null}）
 * @param trajectory      鼠标轨迹分析（可为 {@code null}）
 * @param temporalAnomaly 时序自编码异常分（未加载模型为 0）
 * @param aiScore         端侧 AI 推理分（无模型回退时为 0）
 * @param clickIntervals  原始点击间隔（ms，只读）
 */
public record AnalysisContext(
        ClickIntervalAnalyzer.ClickAnalysis click,
        TrajectoryAnalyzer.TrajectoryAnalysis trajectory,
        double temporalAnomaly,
        double aiScore,
        List<Long> clickIntervals) {

    /** 空上下文：全部无数据。 */
    public static AnalysisContext empty() {
        return new AnalysisContext(ClickIntervalAnalyzer.ClickAnalysis.insufficient(), null, 0.0, 0.0, List.of());
    }
}