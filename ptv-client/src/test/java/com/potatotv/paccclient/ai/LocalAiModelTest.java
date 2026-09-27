package com.potatotv.paccclient.ai;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAiModelTest {

    /** 训练一个微型可分离 XGBoost 并封装为 .paccm 字节。 */
    private static byte[] syntheticModel() {
        Random rng = new Random(5);
        int n = 200;
        double[][] x = new double[n][2];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < n / 2) {
                x[i][0] = rng.nextGaussian() * 0.5;
                x[i][1] = rng.nextGaussian() * 0.5;
                y[i] = 0;
            } else {
                x[i][0] = 4 + rng.nextGaussian() * 0.5;
                x[i][1] = 4 + rng.nextGaussian() * 0.5;
                y[i] = 1;
            }
        }
        XGBoostModel m = XGBoostModel.train(x, y, 40, 3, 0.3, 9);
        byte[] weights = m.serialize();
        return PaccModelFormat.encode(new PaccModelFormat.ModelHeader(
                PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_XGBOOST, 2, weights.length), weights);
    }

    @Test
    void noModelFallsBackAndPreScoreStaysRuleBased() {
        LocalAiModel m = new LocalAiModel();
        assertFalse(m.loaded());
        assertEquals(0, m.modelVersion());

        FeatureVector fv = new FeatureVector().put("a", 1.0).put("b", 0.0);
        InferenceResult r = m.infer(fv);
        assertEquals(InferenceResult.SOURCE_FALLBACK, r.source());
        assertTrue(r.contributions().isEmpty());

        // 旧行为兼容：无模型时 critical 抬升 15
        assertEquals(55, m.preScore(new DetectionEvent("killaura", "critical", 40)));
        assertEquals(40, m.preScore(new DetectionEvent("killaura", "low", 40)));
    }

    @Test
    void loadsSyntheticModelAndInfers() throws Exception {
        LocalAiModel m = new LocalAiModel();
        m.load(syntheticModel());

        assertTrue(m.loaded());
        assertTrue(m.modelVersion() > 0);
        assertNotNull(m.loadedAt());

        FeatureVector fv = new FeatureVector().put("f0", 4.0).put("f1", 4.0);
        InferenceResult r = m.infer(fv);
        assertEquals(InferenceResult.SOURCE_MODEL, r.source());
        assertTrue(r.latencyMs() < 50, "推理延迟应小于 50ms，实际 " + r.latencyMs() + "ms");
        assertTrue(r.score() > 0.5, "正类样本 AI 分应偏高，实际 " + r.score());
        assertFalse(r.contributions().isEmpty());
    }

    @Test
    void garbageBytesRejectedAndStateUnchanged() {
        LocalAiModel m = new LocalAiModel();
        byte[] junk = new byte[64];
        Arrays.fill(junk, (byte) 0x7F);
        assertThrows(IOException.class, () -> m.load(junk));
        assertFalse(m.loaded());
        assertEquals(0, m.modelVersion());
    }
}