package com.potatotv.paccclient.detection.federated;

import com.potatotv.paccclient.ai.AutoencoderModel;
import com.potatotv.paccclient.detection.FeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.2 端侧训练器与参数布局测试：梯度维度与参数布局一致、全部有限且非退化；全局参数可原样还原为
 * 既有自编码器权重布局（联邦向量 ↔ 推理层负载的桥）。
 */
class LocalGradientTrainerTest {

    private static FeatureVector randomSample(Random rng) {
        FeatureVector fv = new FeatureVector();
        for (int k = 0; k < 12; k++) {
            fv.put(FeatureSchema.keys().get(k), rng.nextGaussian());
        }
        return fv;
    }

    @Test
    void gradientHasLayoutDimensionAndIsFinite() {
        LocalGradientTrainer trainer = new LocalGradientTrainer();
        int expected = FederatedParameterLayout.parameterCount(FeatureSchema.size(), 8);
        assertEquals(expected, trainer.gradientDim());

        Random rng = new Random(3);
        List<FeatureVector> samples = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            samples.add(randomSample(rng));
        }

        LocalGradientTrainer.TrainingResult r = trainer.train(samples);

        assertEquals(32, r.sampleCount());
        assertEquals(expected, r.gradient().length);
        assertTrue(GradientCodec.isFinite(r.gradient()), "梯度必须全部有限");
        assertTrue(GradientCodec.l2Norm(r.gradient()) > 0.0, "非零参数下梯度不应退化为 0");
        assertTrue(r.loss() > 0.0, "零初始参数的重构损失应大于 0");
    }

    @Test
    void emptySampleSetProducesZeroGradient() {
        LocalGradientTrainer trainer = new LocalGradientTrainer();
        LocalGradientTrainer.TrainingResult r = trainer.train(List.of());

        assertEquals(0, r.sampleCount());
        assertEquals(0.0, GradientCodec.l2Norm(r.gradient()), 1e-12);
    }

    @Test
    void globalParametersCanBeRestoredIntoAutoencoderLayout() {
        int featureDim = 2;
        int hidden = 3;
        int dim = FederatedParameterLayout.parameterCount(featureDim, hidden);
        assertEquals(2 * featureDim * hidden + hidden + featureDim, dim);

        double[] params = new double[dim];
        Random rng = new Random(9);
        for (int i = 0; i < dim; i++) {
            params[i] = rng.nextGaussian() * 0.1;
        }

        AutoencoderModel model = AutoencoderModel.deserialize(
                FederatedParameterLayout.toAutoencoderBytes(params, featureDim, hidden), featureDim);
        assertEquals(featureDim, model.featureDim());
        assertTrue(Double.isFinite(model.reconstructionError(new double[]{1.0, 2.0})));
    }
}