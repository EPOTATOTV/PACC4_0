package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF §4.1.3 行为模态评分器：移动 / 战斗统计 → 速度 / 飞行 / 杀戮光环。
 *
 * <p>速度倍数、垂直速度、角度变化率、攻击距离与杀人比是同一批服务端友好信号的镜像，
 * 端侧与后端都可计算，便于交叉印证。</p>
 */
@Component
public class BehaviorModalityScorer extends WeightedModalityScorer {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("speed_ratio", 1.5, Direction.HIGH_BAD),
            new Signal("fly_vertical_speed", 1.2, Direction.HIGH_BAD),
            new Signal("killaura_angle_speed", 45.0, Direction.HIGH_BAD),
            new Signal("reach_distance", 4.0, Direction.HIGH_BAD),
            new Signal("kill_death_ratio", 5.0, Direction.HIGH_BAD));

    @Override
    public Modality modality() {
        return Modality.BEHAVIOR;
    }

    @Override
    protected List<Signal> signalSpecs() {
        return SIGNALS;
    }
}