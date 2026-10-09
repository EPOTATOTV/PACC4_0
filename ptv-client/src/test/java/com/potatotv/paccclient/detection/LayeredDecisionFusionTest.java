package com.potatotv.paccclient.detection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三层融合判定（文档 §5.3）：权重 35/25/40、阈值 85/50/20 的纯函数映射验证。
 *
 * <p>这些常量是端侧红屏 / 上报的分档依据，属于对玩家可见的行为边界，
 * 用边界值（恰好等于阈值 / 差一点）钉住，避免后续调权重时静默改变处置档位。</p>
 */
class LayeredDecisionFusionTest {

    @Test
    void 权重之和为1且与文档一致() {
        assertEquals(1.0, LayeredDecision.WEIGHT_NETWORK + LayeredDecision.WEIGHT_VISION
                + LayeredDecision.WEIGHT_SYSTEM, 1e-9, "三层权重之和应为 1");
        assertEquals(0.35, LayeredDecision.WEIGHT_NETWORK, 1e-9);
        assertEquals(0.25, LayeredDecision.WEIGHT_VISION, 1e-9);
        assertEquals(0.40, LayeredDecision.WEIGHT_SYSTEM, 1e-9);
    }

    @Test
    void 融合分按加权求和并封顶() {
        assertEquals(0.0, LayeredDecision.fusedRisk(0, 0, 0), 1e-9);
        assertEquals(100.0, LayeredDecision.fusedRisk(100, 100, 100), 1e-9);
        assertEquals(28.0, LayeredDecision.fusedRisk(80, 0, 0), 1e-9, "网络层 80 分 → 80×0.35");
        assertEquals(25.0, LayeredDecision.fusedRisk(0, 100, 0), 1e-9, "屏幕层 100 分 → 100×0.25");
        assertEquals(40.0, LayeredDecision.fusedRisk(0, 0, 100), 1e-9, "系统层 100 分 → 100×0.40");
        assertEquals(63.0, LayeredDecision.fusedRisk(100, 80, 20), 1e-9, "35 + 20 + 8");
    }

    @Test
    void 越界值按夹取非有限值按无证据处理() {
        assertEquals(35.0, LayeredDecision.fusedRisk(120, -5, 0), 1e-9, "超界按 100 夹取，负数按 0");
        // 非有限值（NaN / 无穷）按「无证据」处理，与 FeatureVector.putExtended 的落盘口径一致：
        // 上游算错不能变成端侧红屏，宁可少报。
        assertEquals(0.0, LayeredDecision.fusedRisk(Double.NaN, 0, 0), 1e-9, "NaN 视为无证据");
        assertEquals(0.0, LayeredDecision.fusedRisk(0, 0, Double.POSITIVE_INFINITY), 1e-9, "无穷视为无证据");
        assertEquals(40.0, LayeredDecision.fusedRisk(0, 0, 100.0), 1e-9, "有限满分正常参与加权");
    }

    @Test
    void 风险分档阈值与文档一致() {
        assertEquals(LayeredDecision.Decision.LOCAL_BLOCK, LayeredDecision.decideByRisk(85.0), "≥85 红屏");
        assertEquals(LayeredDecision.Decision.LOCAL_BLOCK, LayeredDecision.decideByRisk(100.0));
        assertEquals(LayeredDecision.Decision.REPORT_CLOUD, LayeredDecision.decideByRisk(84.9), "85 以下转上报");
        assertEquals(LayeredDecision.Decision.REPORT_CLOUD, LayeredDecision.decideByRisk(50.0), "≥50 上报云端");
        assertEquals(LayeredDecision.Decision.PERIODIC, LayeredDecision.decideByRisk(49.9));
        assertEquals(LayeredDecision.Decision.PERIODIC, LayeredDecision.decideByRisk(19.9), "20 以下周期上报");
    }

    @Test
    void 单层满分的融合分不足以红屏() {
        // 三层架构的核心约束：任一单层证据拉满也只能到「上报」档，
        // 红屏必须多源印证（文档 §5.2 / 设计原则 4）
        assertTrue(LayeredDecision.fusedRisk(100, 0, 0) < LayeredDecision.RISK_REDSCREEN);
        assertTrue(LayeredDecision.fusedRisk(0, 100, 0) < LayeredDecision.RISK_REDSCREEN);
        assertTrue(LayeredDecision.fusedRisk(0, 0, 100) < LayeredDecision.RISK_REDSCREEN);
        // 网络 + 系统两层拉满才够红屏（35 + 40 = 75 还不够），需屏幕层再补：
        assertEquals(LayeredDecision.Decision.REPORT_CLOUD, LayeredDecision.decideByRisk(
                LayeredDecision.fusedRisk(100, 0, 100)), "网络+系统 75 分仍属上报档");
        assertEquals(LayeredDecision.Decision.LOCAL_BLOCK, LayeredDecision.decideByRisk(
                LayeredDecision.fusedRisk(100, 100, 100)), "三层齐满 100 才红屏");
    }

    @Test
    void 融合分的上报原因映射() {
        assertEquals(FeatureReport.TriggerReason.AI_LOW_CONFIDENCE,
                LayeredDecision.riskTriggerReason(50.0));
        assertEquals(FeatureReport.TriggerReason.PERIODIC,
                LayeredDecision.riskTriggerReason(49.9));
        assertEquals(FeatureReport.TriggerReason.PERIODIC,
                LayeredDecision.riskTriggerReason(20.0), "记录档并入周期性上报");
    }
}