package com.potatotv.paccclient.detection.federated;

import com.potatotv.paccclient.ai.InferenceResult;
import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.FeatureVector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.2 聚合模型下发测试：合法模型装载进既有推理层；损坏/摘要不符/维度不符的下发被拒绝，且
 * 上一版模型（回退位）保持可用。
 */
class FederatedModelDownlinkTest {

    private static double[] parameters(int dim, long seed) {
        Random rng = new Random(seed);
        double[] p = new double[dim];
        for (int i = 0; i < dim; i++) {
            p[i] = rng.nextGaussian() * 0.01;
        }
        return p;
    }

    private static FeatureVector sample() {
        FeatureVector fv = new FeatureVector();
        fv.put("feature_click_cps", 3.0);
        fv.put("feature_speed_ratio", 1.0);
        return fv;
    }

    @Test
    void validModelIsAdoptedIntoTheInferenceLayer() {
        FederatedSettings settings = FederatedSettings.defaults();
        LocalAiModel model = new LocalAiModel();
        FederatedModelDownlink downlink = new FederatedModelDownlink("", "", model, settings);
        double[] params = parameters(settings.gradientDim(), 11);
        String sha = GradientCodec.sha256Hex(params);

        FederatedModelDownlink.AdoptionResult r = downlink.adopt(payload("1", settings, params, sha));

        assertTrue(r.accepted(), r.reason());
        assertEquals("1", downlink.currentVersion());
        assertEquals(sha, downlink.currentSha256());
        assertEquals(sha, r.sha256());
        assertTrue(model.loaded(), "模型应已装载进既有推理层");
        assertEquals(1, model.modelVersion());
        assertEquals(InferenceResult.SOURCE_MODEL, model.infer(sample()).source());
    }

    @Test
    void corruptedModelIsRejectedWithPreviousVersionPreserved() {
        FederatedSettings settings = FederatedSettings.defaults();
        LocalAiModel model = new LocalAiModel();
        FederatedModelDownlink downlink = new FederatedModelDownlink("", "", model, settings);

        double[] good = parameters(settings.gradientDim(), 23);
        assertTrue(downlink.adopt(payload("7", settings, good, GradientCodec.sha256Hex(good))).accepted());
        int adoptedVersion = model.modelVersion();
        assertEquals("7", downlink.currentVersion());

        // 1）非有限权重
        double[] nan = good.clone();
        nan[3] = Double.NaN;
        FederatedModelDownlink.AdoptionResult r1 = downlink.adopt(payload("8", settings, nan, ""));
        assertFalse(r1.accepted());
        assertTrue(r1.reason().contains("非有限值"), r1.reason());

        // 2）摘要不符
        FederatedModelDownlink.AdoptionResult r2 = downlink.adopt(payload("9", settings, good, "deadbeef"));
        assertFalse(r2.accepted());
        assertTrue(r2.reason().contains("摘要不匹配"), r2.reason());

        // 3）维度不符
        FederatedModelDownlink.AdoptionResult r3 = downlink.adopt(payload("10", settings, new double[]{1.0, 2.0}, ""));
        assertFalse(r3.accepted());
        assertTrue(r3.reason().contains("维度"), r3.reason());

        // 回退位保留：版本、权重与推理可用性都不受影响
        assertEquals(adoptedVersion, model.modelVersion());
        assertEquals("7", downlink.currentVersion());
        assertEquals(InferenceResult.SOURCE_MODEL, model.infer(sample()).source());
    }

    @Test
    void emptyPayloadIsRejected() {
        FederatedModelDownlink downlink = new FederatedModelDownlink("", "",
                new LocalAiModel(), FederatedSettings.defaults());
        assertFalse(downlink.adopt(Map.of()).accepted());
        assertFalse(downlink.adopt(null).accepted());
    }

    /** 构造一条与云端 FederatedModel 视图同形的下发载荷（weights 用逗号分隔串，与 GradientCodec 对齐）。 */
    private static Map<String, Object> payload(String version, FederatedSettings settings,
                                               double[] params, String sha) {
        return Map.<String, Object>of(
                "version", version,
                "feature_dim", settings.featureDim(),
                "weights", GradientCodec.encode(params),
                "weights_sha256", sha);
    }
}