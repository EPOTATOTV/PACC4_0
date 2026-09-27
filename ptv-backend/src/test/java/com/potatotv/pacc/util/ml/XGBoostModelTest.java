package com.potatotv.pacc.util.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §6.1 XGBoost 训练/推理确定性单测：线性可分数据上的类别分离、{@code .paccm} 容器往返一致性与校验和拒收。
 */
class XGBoostModelTest {

    /** 三类行为特征（其中第三维无判别力，用于验证特征子采样不会破坏分离能力）。 */
    private static final int DIM = 3;

    /** 合成线性可分数据：作弊样本 x[0]≈+2、正常样本 x[0]≈-2，各维带确定性小幅抖动。 */
    private static double[][] separable(boolean[] cheatFlags) {
        double[][] x = new double[cheatFlags.length][DIM];
        for (int i = 0; i < cheatFlags.length; i++) {
            double center = cheatFlags[i] ? 2.0 : -2.0;
            x[i][0] = center + 0.3 * Math.sin(i * 1.3);
            x[i][1] = center * 0.5 + 0.2 * Math.cos(i * 0.7);
            x[i][2] = 0.1 * Math.sin(i * 2.1);
        }
        return x;
    }

    private static double[] labels(boolean[] cheatFlags) {
        double[] y = new double[cheatFlags.length];
        for (int i = 0; i < cheatFlags.length; i++) {
            y[i] = cheatFlags[i] ? 1.0 : 0.0;
        }
        return y;
    }

    private static boolean[] alternating(int n) {
        boolean[] flags = new boolean[n];
        for (int i = 0; i < n; i++) {
            flags[i] = i % 2 == 1;
        }
        return flags;
    }

    private static XGBoostModel trainSeparable() {
        boolean[] flags = alternating(80);
        return XGBoostModel.train(separable(flags), labels(flags), 40, 3, 0.4, 42L);
    }

    @Test
    void separatesLinearlySeparableClasses() {
        boolean[] flags = alternating(80);
        double[][] x = separable(flags);
        double[] y = labels(flags);
        XGBoostModel model = XGBoostModel.train(x, y, 40, 3, 0.4, 42L);

        assertEquals(DIM, model.featureDim());
        assertEquals(40, model.treeCount());
        assertTrue(model.predict(new double[]{2.0, 1.0, 0.0}) > 0.8, "作弊样本应判为高概率");
        assertTrue(model.predict(new double[]{-2.0, -1.0, 0.0}) < 0.2, "正常样本应判为低概率");

        // 训练集内所有作弊样本的预测概率都应高于所有正常样本
        double minCheat = 1.0;
        double maxClean = 0.0;
        for (int i = 0; i < x.length; i++) {
            double p = model.predict(x[i]);
            if (y[i] > 0.5) {
                minCheat = Math.min(minCheat, p);
            } else {
                maxClean = Math.max(maxClean, p);
            }
        }
        assertTrue(minCheat > maxClean, "类别未分离：minCheat=" + minCheat + " maxClean=" + maxClean);
    }

    @Test
    void featureContributionsSumToOneAndHighlightDiscriminativeFeature() {
        boolean[] flags = alternating(80);
        XGBoostModel model = XGBoostModel.train(separable(flags), labels(flags), 40, 3, 0.4, 42L);

        double[] contrib = model.featureContributions(new double[]{2.0, 1.0, 0.0});
        assertEquals(DIM, contrib.length);
        double sum = 0;
        for (double c : contrib) {
            assertTrue(c >= 0);
            sum += c;
        }
        assertEquals(1.0, sum, 1e-9, "贡献应归一化到 1");
        assertTrue(contrib[0] > contrib[2], "判别性特征（x[0]）的贡献应高于噪声特征（x[2]）");
    }

    @Test
    void sameSeedProducesIdenticalModelBytes() {
        assertArrayEquals(trainSeparable().toPaccmBytes(DIM), trainSeparable().toPaccmBytes(DIM),
                "固定 seed 下训练结果必须可复现");
    }

    @Test
    void paccmRoundTripAndChecksumRejection() {
        XGBoostModel model = trainSeparable();
        byte[] bytes = model.toPaccmBytes(DIM);

        PaccModelFormat.PaccModel parsed = PaccModelFormat.read(bytes);
        assertEquals(PaccModelFormat.FORMAT_VERSION, parsed.formatVersion());
        assertEquals(PaccModelFormat.TYPE_XGBOOST, parsed.modelType());
        assertEquals(DIM, parsed.featureDim());
        assertArrayEquals(bytes, PaccModelFormat.encode(parsed.modelType(), parsed.featureDim(), parsed.weights()),
                "解析后再编码应逐字节还原");

        // 权重区任一字节被篡改 → 尾部 SHA-256 校验失败
        byte[] tampered = bytes.clone();
        tampered[16] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> PaccModelFormat.read(tampered));

        // 长度被截断 → 结构非法
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
        assertThrows(IllegalArgumentException.class, () -> PaccModelFormat.read(truncated));
    }

    @Test
    void rejectsMismatchedFeatureDimAndInvalidParams() {
        XGBoostModel model = trainSeparable();
        assertThrows(IllegalArgumentException.class, () -> model.toPaccmBytes(DIM + 1));
        assertThrows(IllegalArgumentException.class, () -> model.predict(new double[DIM + 1]));
        boolean[] flags = alternating(20);
        assertThrows(IllegalArgumentException.class, () -> XGBoostModel.train(separable(flags), labels(flags), 0, 3, 0.4, 1L));
        assertThrows(IllegalArgumentException.class, () -> XGBoostModel.train(separable(flags), new double[]{0, 1}, 10, 3, 0.4, 1L));
    }
}