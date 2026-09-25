package com.potatotv.pacc.service.detection.df.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DF §4.1.3 多模态融合测试：三种策略置于同一接口后由调用方选择；缺失模态按权重重新归一化优雅降级
 * （不报错、也不把缺失当「正常」）；融合结果记录各模态贡献。
 *
 * <p>评分器与策略直接构造真实 Bean（非 mock），因此断言的是实际评分与融合数值，而非交互次数。</p>
 */
class MultimodalFusionServiceTest {

    private static final List<String> STRATEGIES = List.of("early", "late", "hybrid");

    private final MultimodalFusionService service = new MultimodalFusionService(
            List.of(new InputModalityScorer(), new MemoryModalityScorer(), new NetworkModalityScorer(),
                    new BehaviorModalityScorer(), new ImageModalityScorer()),
            List.of(new EarlyFusionStrategy(), new LateFusionStrategy(), new HybridFusionStrategy()),
            1.0, 1.0, 0.8, 1.0, 0.8);

    /** 三种策略都必须可用，且结果标注所用策略名。 */
    @Test
    void allThreeStrategiesAreAvailableAndLabelled() {
        assertEquals(STRATEGIES, service.availableStrategies());
        for (String strategy : STRATEGIES) {
            FusedResult result = service.analyze(strategy, cheatModalities(), null);
            assertEquals(strategy, result.strategy());
        }
    }

    /** 五模态同时异常（明显外挂）在任何策略下都应判为作弊及以上。 */
    @Test
    void obviousCheatIsFlaggedByEveryStrategy() {
        for (String strategy : STRATEGIES) {
            FusedResult result = service.analyze(strategy, cheatModalities(), null);
            System.out.printf("[融合] strategy=%-6s fused=%.4f verdict=%s%n",
                    strategy, result.fusedScore(), result.verdict());
            assertTrue(result.fusedScore() >= 0.65,
                    strategy + " 融合分 " + result.fusedScore() + " 未达 CHEAT 门限");
            assertTrue(result.missingModalities().isEmpty(), "五模态齐备不应报告缺失");
            assertEquals(5, result.modalityScores().size());
        }
    }

    /** 正常输入在各策略下不得误报。 */
    @Test
    void cleanInputStaysSafe() {
        Map<Modality, ModalityInput> clean = new EnumMap<>(Modality.class);
        clean.put(Modality.INPUT, new ModalityInput(Map.of(
                "click_cps", 6.0, "aim_angle_speed", 20.0, "aim_smoothness", 0.3,
                "click_interval_cv", 0.4, "script_regularity", 0.1)));
        clean.put(Modality.BEHAVIOR, new ModalityInput(Map.of(
                "speed_ratio", 1.0, "fly_vertical_speed", 0.0, "killaura_angle_speed", 10.0,
                "reach_distance", 2.5, "kill_death_ratio", 1.0)));
        for (String strategy : STRATEGIES) {
            FusedResult result = service.analyze(strategy, clean, null);
            assertEquals("SAFE", result.verdict(), strategy + " 误报：" + result.fusedScore());
        }
    }

    /** 缺失模态：权重重新归一化到仅剩的出现模态，不报错。 */
    @Test
    void missingModalitiesAreRenormalizedNotRejected() {
        Map<Modality, ModalityInput> onlyInput = Map.of(Modality.INPUT,
                new ModalityInput(Map.of("click_cps", 20.0, "script_regularity", 1.0)));

        FusedResult result = service.analyze("late", onlyInput, null);

        assertEquals(1, result.weightsUsed().size());
        assertEquals(1.0, result.weightsUsed().get(Modality.INPUT), 1e-9);
        assertEquals(List.of(Modality.MEMORY, Modality.NETWORK, Modality.BEHAVIOR, Modality.IMAGE),
                result.missingModalities());
        assertTrue(result.fusedScore() > 0.0, "仅剩的模态仍应参与融合");
    }

    /** 出现模态的生效权重之和恒为 1（缺失不会把总分整体缩水）。 */
    @Test
    void usedWeightsSumToOneForPresentModalities() {
        Map<Modality, ModalityInput> twoModalities = new EnumMap<>(Modality.class);
        twoModalities.put(Modality.MEMORY, new ModalityInput(Map.of("memory_write_events", 8.0)));
        twoModalities.put(Modality.NETWORK, new ModalityInput(Map.of("proxy_flag", 1.0)));

        FusedResult result = service.analyze("late", twoModalities, null);
        double sum = result.weightsUsed().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-9);
        assertEquals(3, result.missingModalities().size());
    }

    /** 全模态缺失：不抛异常，返回零分 SAFE 与完整缺失清单。 */
    @Test
    void allModalitiesMissingDegradesGracefully() {
        for (String strategy : STRATEGIES) {
            FusedResult result = service.analyze(strategy, Map.of(), null);
            assertEquals(0.0, result.fusedScore(), 1e-9);
            assertEquals("SAFE", result.verdict());
            assertEquals(5, result.missingModalities().size());
            assertTrue(result.weightsUsed().isEmpty());
        }
    }

    /** 载荷为空的模态视同缺失。 */
    @Test
    void emptyPayloadCountsAsMissing() {
        FusedResult result = service.analyze("hybrid",
                Map.of(Modality.INPUT, new ModalityInput(Map.of())), null);
        assertTrue(result.missingModalities().contains(Modality.INPUT));
        assertFalse(result.scoreOf(Modality.INPUT).present());
    }

    /** 未知策略必须显式失败，而不是静默降级。 */
    @Test
    void unknownStrategyIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.analyze("stacking", cheatModalities(), null));
        assertTrue(e.getMessage().contains("stacking"));
    }

    /** 策略名大小写不敏感，空名按 hybrid。 */
    @Test
    void strategyNameIsCaseInsensitiveAndDefaultsToHybrid() {
        assertEquals("early", service.analyze("EARLY", cheatModalities(), null).strategy());
        assertEquals("hybrid", service.analyze("  ", cheatModalities(), null).strategy());
        assertEquals("hybrid", service.analyze(null, cheatModalities(), null).strategy());
    }

    /** 权重覆盖生效；非正权重被忽略（回退默认）。 */
    @Test
    void weightOverridesApplyAndInvalidOnesAreIgnored() {
        FusedResult boosted = service.analyze("late", cheatModalities(),
                Map.of("network", 5.0, "image", -1.0));
        assertTrue(boosted.weightsUsed().get(Modality.NETWORK) > boosted.weightsUsed().get(Modality.INPUT),
                "network 权重被抬高后其生效权重应超过 input");
        double sum = boosted.weightsUsed().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-9);
        assertEquals(0.8, service.defaultWeightsView().get("image"), 1e-9);
    }

    /** 融合结果记录每个出现模态的加权贡献，缺失模态不出现。 */
    @Test
    void contributionsCoverPresentModalitiesOnly() {
        FusedResult result = service.analyze("late",
                Map.of(Modality.BEHAVIOR, new ModalityInput(Map.of("speed_ratio", 3.0))), null);
        assertEquals(1, result.contributions().size());
        assertTrue(result.contributions().containsKey(Modality.BEHAVIOR));
        assertFalse(result.contributions().containsKey(Modality.IMAGE));
    }

    /** 单模态评分器：命中信号数越多置信度越高，且证据可读。 */
    @Test
    void scorerConfidenceRisesWithSignalCoverage() {
        ModalityScore sparse = new InputModalityScorer()
                .score(new ModalityInput(Map.of("click_cps", 20.0)));
        ModalityScore dense = new InputModalityScorer()
                .score(new ModalityInput(Map.of("click_cps", 20.0, "aim_angle_speed", 60.0,
                        "aim_smoothness", 0.01, "click_interval_cv", 0.01, "script_regularity", 1.0)));

        assertTrue(dense.confidence() > sparse.confidence());
        assertEquals(1.0, dense.confidence(), 1e-9);
        assertTrue(dense.score() > 0.5);
        assertFalse(dense.evidence().isEmpty());
    }

    // ------------------------------ 辅助 ------------------------------

    /** 五模态齐备的明显外挂载荷。 */
    private static Map<Modality, ModalityInput> cheatModalities() {
        Map<Modality, ModalityInput> inputs = new EnumMap<>(Modality.class);
        inputs.put(Modality.INPUT, new ModalityInput(Map.of(
                "click_cps", 20.0, "aim_angle_speed", 60.0, "aim_smoothness", 0.02,
                "click_interval_cv", 0.01, "script_regularity", 0.9)));
        inputs.put(Modality.MEMORY, new ModalityInput(Map.of(
                "memory_write_events", 10.0, "injected_module_count", 2.0, "hook_count", 5.0,
                "page_guard_hits", 2.0, "unknown_region_ratio", 0.5)));
        inputs.put(Modality.NETWORK, new ModalityInput(Map.of(
                "proxy_flag", 1.0, "vpn_flag", 1.0, "dns_anomaly_score", 0.9,
                "connection_fanout", 60.0, "latency_jitter_ms", 100.0)));
        inputs.put(Modality.BEHAVIOR, new ModalityInput(Map.of(
                "speed_ratio", 2.0, "fly_vertical_speed", 2.0, "killaura_angle_speed", 90.0,
                "reach_distance", 6.0, "kill_death_ratio", 10.0)));
        inputs.put(Modality.IMAGE, new ModalityInput(Map.of(
                "ocr_esp_hud", 1.0, "ocr_aim_overlay", 1.0, "ocr_cheat_menu", 1.0,
                "ocr_confidence", 0.95, "ocr_unknown_panel", 0.8)));
        return inputs;
    }
}