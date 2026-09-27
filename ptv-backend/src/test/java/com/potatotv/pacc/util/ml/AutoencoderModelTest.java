package com.potatotv.pacc.util.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * v5.2 §6.1 自编码器确定性单测：训练确实降低重构误差、离群样本的重构误差高于内点、{@code .paccm} 容器往返一致。
 */
class AutoencoderModelTest {

    private static final int DIM = 2;

    /**
     * 二维直线结构上的正常样本（y = 2x）：一维隐层足以拟合，故训练应显著降低重构误差。
     */
    private static double[][] onLine(int n) {
        double[][] x = new double[n][DIM];
        for (int i = 0; i < n; i++) {
            double t = -2.0 + 4.0 * i / (n - 1);
            x[i][0] = t + 0.05 * Math.sin(i * 0.9);
            x[i][1] = 2.0 * t + 0.05 * Math.cos(i * 1.1);
        }
        return x;
    }

    private static double meanError(AutoencoderModel model, double[][] samples) {
        double sum = 0;
        for (double[] row : samples) {
            sum += model.reconstructionError(row);
        }
        return sum / samples.length;
    }

    @Test
    void trainingReducesReconstructionError() {
        double[][] samples = onLine(60);
        AutoencoderModel untrained = AutoencoderModel.fit(samples, 1, 0, 0.05, 7L);
        AutoencoderModel trained = AutoencoderModel.fit(samples, 1, 400, 0.05, 7L);

        double before = meanError(untrained, samples);
        double after = meanError(trained, samples);
        assertTrue(Double.isFinite(before) && Double.isFinite(after), "重构误差应为有限值");
        assertTrue(after < before,
                "训练后平均重构误差应下降：before=" + before + " after=" + after);
    }

    @Test
    void outlierScoresHigherThanInlier() {
        AutoencoderModel model = AutoencoderModel.fit(onLine(60), 1, 400, 0.05, 7L);

        double inlier = model.reconstructionError(new double[]{1.0, 2.0});
        // 偏离正常流形（直线 y = 2x）的样本：残差方向与主方向正交
        double outlier = model.reconstructionError(new double[]{-2.0, 4.0});
        assertTrue(outlier > inlier, "离群样本的重构误差应高于内点：inlier=" + inlier + " outlier=" + outlier);
    }

    @Test
    void sameSeedProducesIdenticalModelBytes() {
        double[][] samples = onLine(40);
        assertArrayEquals(AutoencoderModel.fit(samples, 1, 50, 0.05, 11L).toPaccmBytes(),
                AutoencoderModel.fit(samples, 1, 50, 0.05, 11L).toPaccmBytes(),
                "固定 seed 下自编码器参数必须可复现");
    }

    @Test
    void paccmRoundTripAndInvalidParamsRejected() {
        double[][] samples = onLine(40);
        AutoencoderModel model = AutoencoderModel.fit(samples, 1, 50, 0.05, 11L);
        byte[] bytes = model.toPaccmBytes();

        PaccModelFormat.PaccModel parsed = PaccModelFormat.read(bytes);
        assertEquals(PaccModelFormat.TYPE_AUTOENCODER, parsed.modelType());
        assertEquals(DIM, parsed.featureDim());
        assertArrayEquals(bytes, PaccModelFormat.encode(parsed.modelType(), parsed.featureDim(), parsed.weights()),
                "解析后再编码应逐字节还原");

        assertThrows(IllegalArgumentException.class, () -> model.reconstructionError(new double[DIM + 1]));
        assertThrows(IllegalArgumentException.class, () -> AutoencoderModel.fit(new double[0][DIM], 1, 10, 0.05, 1L));
        assertThrows(IllegalArgumentException.class, () -> AutoencoderModel.fit(samples, 0, 10, 0.05, 1L));
        assertThrows(IllegalArgumentException.class, () -> AutoencoderModel.fit(samples, 1, -1, 0.05, 1L));
    }
}