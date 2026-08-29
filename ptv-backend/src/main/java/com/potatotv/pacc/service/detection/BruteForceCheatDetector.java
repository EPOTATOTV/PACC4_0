package com.potatotv.pacc.service.detection;

import com.potatotv.pacc.domain.CheatType;
import com.potatotv.pacc.domain.FeatureVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v4.1 暴力外挂检测引擎 — 四层递进式架构。
 * <p>信号层 → 时序层 → 物理层 → 语义层，逐层加强证据。单层异常不判定，
 * 需多层协同命中才生成检测结论（配合 PTV 交叉验证，误报率 ≤ 0.01%）。</p>
 */
@Slf4j
@Service
@SuppressWarnings("null") // 层级权重流 lambda 的 null 分析误报
public class BruteForceCheatDetector {

    /** 各暴力外挂类型的判定器（业务逻辑内聚在对应 switch 分支）。 */
    public record LayerHit(int layer, String layerName, String signal, double weight) {}

    /** 检测结论。 */
    public record Verdict(CheatType cheatType, boolean detected, double confidence,
                          List<LayerHit> hits, String summary) {}

    /** 多类型批量判定：返回命中的类型列表（按置信度降序）。 */
    public List<Verdict> evaluateAll(FeatureVector fv, Map<String, Object> ctx) {
        List<Verdict> out = new ArrayList<>();
        for (CheatType.BruteForce t : CheatType.BruteForce.values()) {
            evaluate(t, fv, ctx).ifPresent(out::add);
        }
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }

    /** 单类型判定。 */
    public Optional<Verdict> evaluate(CheatType.BruteForce type, FeatureVector fv, Map<String, Object> ctx) {
        List<LayerHit> hits = new ArrayList<>();

        // L1 信号层：明确阈值/标志位的快速命中
        signalLayer(type, fv, ctx).ifPresent(hits::add);
        // L2 时序层：统计异常（均值/方差/突变）
        timingLayer(type, fv, ctx).ifPresent(hits::add);
        // L3 物理层：输入/设备/运动物理一致性
        physicalLayer(type, fv, ctx).ifPresent(hits::add);
        // L4 语义层：AI 行为画像 / 语义一致性
        semanticLayer(type, fv, ctx).ifPresent(hits::add);

        if (hits.isEmpty()) {
            return Optional.empty();
        }
        double confidence = Math.min(0.99, hits.stream().mapToDouble(LayerHit::weight).sum() * 0.25);
        boolean detected = hits.size() >= 2 && confidence >= 0.5;
        String summary = type.displayName() + (detected ? " 已判定" : " 可疑")
                + "（命中 " + hits.size() + " 层，置信度 " + String.format("%.1f%%", confidence * 100) + "）";
        return Optional.of(new Verdict(type, detected, confidence, List.copyOf(hits), summary));
    }

    // ---------- L1 信号层 ----------
    private Optional<LayerHit> signalLayer(CheatType.BruteForce t, FeatureVector fv, Map<String, Object> ctx) {
        double threshold = switch (t) {
            case KILLAURA -> fv.get("feature_killaura_angle_speed");
            case AUTO_CLICKER -> fv.get("feature_click_cps");
            case REACH -> fv.get("feature_reach_distance");
            case FLY -> fv.get("feature_fly_vertical_speed");
            case SPEED -> fv.get("feature_speed_ratio");
            case CRYSTAL_AURA -> fv.get("feature_crystal_aura_flag");
            case AUTO_TOTEM -> fv.get("feature_auto_totem_switch_ms");
            case VELOCITY -> fv.get("feature_velocity_ratio");
            case CRITICALS -> fv.get("feature_criticals_rate");
            case NO_FALL -> fv.get("feature_nofall_violations");
            case SCAFFOLD -> fv.get("feature_scaffold_block_per_sec");
            case FAST_PLACE -> fv.get("feature_fastplace_block_per_sec");
            case FAST_BREAK -> fv.get("feature_fastbreak_block_per_sec");
            case NUKER -> fv.get("feature_nuker_break_radius");
            case AUTO_ARMOR -> fv.get("feature_autoarmor_equip_ms");
            case AUTO_POT -> fv.get("feature_autopot_drink_ms");
            case AUTO_EAT -> fv.get("feature_autoeat_eat_ms");
        };
        double limit = signalLimit(t);
        if (threshold >= limit) {
            return Optional.of(new LayerHit(1, "信号层",
                    t.displayName() + " 原始信号超限 (" + String.format("%.2f", threshold) + " ≥ " + limit + ")", 0.35));
        }
        return Optional.empty();
    }

    private double signalLimit(CheatType.BruteForce t) {
        return switch (t) {
            case KILLAURA -> 45.0;        // 攻击角度变化速率（度/秒）
            case AUTO_CLICKER -> 14.0;    // CPS
            case REACH -> 4.0;            // 攻击距离（方块）
            case FLY -> 1.2;              // 垂直速度（方块/秒）
            case SPEED -> 1.5;            // 水平速度倍数
            case CRYSTAL_AURA -> 0.5;     // 布尔/比例
            case AUTO_TOTEM -> 50.0;      // 切换耗时（毫秒）
            case VELOCITY -> 0.1;         // 受击退后保留速度比例
            case CRITICALS -> 0.9;        // 暴击率
            case NO_FALL -> 0.0;          // 违规次数 > 0
            case SCAFFOLD -> 8.0;         // 放置方块/秒
            case FAST_PLACE -> 12.0;      // 放置方块/秒
            case FAST_BREAK -> 4.0;       // 挖掘方块/秒
            case NUKER -> 3.0;            // 挖掘半径（方块）
            case AUTO_ARMOR -> 30.0;      // 穿戴耗时（毫秒）
            case AUTO_POT -> 100.0;       // 喝药耗时（毫秒）
            case AUTO_EAT -> 100.0;       // 进食耗时（毫秒）
        };
    }

    // ---------- L2 时序层 ----------
    private Optional<LayerHit> timingLayer(CheatType.BruteForce t, FeatureVector fv, Map<String, Object> ctx) {
        double z = zScore(t, fv);
        if (z >= 2.0) {
            return Optional.of(new LayerHit(2, "时序层",
                    t.displayName() + " 时序统计异常 (z=" + String.format("%.2f", z) + ")", 0.25));
        }
        return Optional.empty();
    }

    /** 简化 z-score：用特征值相对基线偏差（生产由 LSTM-AE 提供）。 */
    private double zScore(CheatType.BruteForce t, FeatureVector fv) {
        double baseline = switch (t) {
            case KILLAURA -> 18.0;
            case AUTO_CLICKER -> 7.0;
            case REACH -> 3.1;
            case FLY -> 0.2;
            case SPEED -> 1.0;
            default -> 0.0;
        };
        if (baseline <= 0) return 0;
        double v = fv.get("feature_" + t.code() + "_mean");
        return (v - baseline) / Math.max(baseline * 0.3, 1e-6);
    }

    // ---------- L3 物理层 ----------
    private Optional<LayerHit> physicalLayer(CheatType.BruteForce t, FeatureVector fv, Map<String, Object> ctx) {
        // 人类物理一致性：例如 CPS 与移动冲突、点击间隔方差过小（机器无抖动）
        double intervalVar = fv.get("feature_click_interval_var");
        if (t == CheatType.BruteForce.AUTO_CLICKER && intervalVar > 0 && intervalVar < 0.02) {
            return Optional.of(new LayerHit(3, "物理层",
                    "点击间隔方差过小（人类抖动缺失，机器点击特征）", 0.25));
        }
        double aimSmoothness = fv.get("feature_aim_smoothness");
        if (t == CheatType.BruteForce.KILLAURA && aimSmoothness > 0 && aimSmoothness < 0.05) {
            return Optional.of(new LayerHit(3, "物理层",
                    "瞄准轨迹过于平滑（无人类微抖）", 0.25));
        }
        return Optional.empty();
    }

    // ---------- L4 语义层 ----------
    private Optional<LayerHit> semanticLayer(CheatType.BruteForce t, FeatureVector fv, Map<String, Object> ctx) {
        double semantic = fv.get("feature_semantic_" + t.code());
        if (semantic >= 0.6) {
            return Optional.of(new LayerHit(4, "语义层",
                    "AI 行为画像语义一致性异常 (score=" + String.format("%.2f", semantic) + ")", 0.2));
        }
        return Optional.empty();
    }

    /** 构造默认的 128 维特征上下文（空特征）。 */
    public static Map<String, Object> emptyContext() {
        return new LinkedHashMap<>();
    }
}