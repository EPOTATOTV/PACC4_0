package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF §4.1.3 输入模态评分器：鼠标 / 键盘原始事件 → 连点器 / 自瞄 / 脚本。
 *
 * <p>高 CPS、超高角度变化率指向连点器与自瞄；而「点击间隔变异系数极小」「瞄准平滑度极高」
 * 这类「比人类更像机器」的低值信号，是脚本化输入的典型指纹。</p>
 */
@Component
public class InputModalityScorer extends WeightedModalityScorer {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("click_cps", 14.0, Direction.HIGH_BAD),
            new Signal("aim_angle_speed", 45.0, Direction.HIGH_BAD),
            new Signal("aim_smoothness", 0.08, Direction.LOW_BAD),
            new Signal("click_interval_cv", 0.05, Direction.LOW_BAD),
            new Signal("script_regularity", 0.8, Direction.HIGH_BAD));

    @Override
    public Modality modality() {
        return Modality.INPUT;
    }

    @Override
    protected List<Signal> signalSpecs() {
        return SIGNALS;
    }
}