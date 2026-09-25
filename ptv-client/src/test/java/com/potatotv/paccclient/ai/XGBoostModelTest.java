package com.potatotv.paccclient.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XGBoostModelTest {

    /** 生成线性可分 2-D 数据集：负类中心 (0,0)，正类中心 (4,4)。 */
    private static double[][] separable(double[] y, long seed) {
        Random rng = new Random(seed);
        int n = 200;
        double[][] x = new double[n][2];
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
        return x;
    }

    @Test
    void trainsSeparableData() {
        double[] y = new double[200];
        double[][] x = separable(y, 42);
        XGBoostModel m = XGBoostModel.train(x, y, 60, 4, 0.3, 7);
        assertTrue(m.treeCount() > 0);
        assertTrue(m.maxDepth() <= XGBoostModel.MAX_DEPTH);
        assertEquals(2, m.featureDim());

        double pos = 0, neg = 0;
        for (int i = 0; i < x.length; i++) {
            double s = m.predict(x[i]);
            if (y[i] == 1) pos += s;
            else neg += s;
        }
        pos /= (x.length / 2);
        neg /= (x.length / 2);
        assertTrue(pos > neg + 0.3, "正类均分应显著高于负类: " + pos + " vs " + neg);
    }

    @Test
    void serializeRoundTripIsExact() {
        double[] y = new double[200];
        double[][] x = separable(y, 42);
        XGBoostModel m = XGBoostModel.train(x, y, 30, 3, 0.3, 7);
        XGBoostModel back = XGBoostModel.deserialize(m.serialize(), 2);
        assertEquals(m.treeCount(), back.treeCount());
        assertEquals(m.maxDepth(), back.maxDepth());
        for (double[] row : x) {
            assertEquals(m.predict(row), back.predict(row), 1e-9);
            assertArrayEquals(m.featureContributions(row), back.featureContributions(row), 1e-9);
        }
    }

    @Test
    void deterministicForSameSeed() {
        double[] y = new double[200];
        double[][] x = separable(y, 42);
        XGBoostModel a = XGBoostModel.train(x, y, 20, 3, 0.3, 99);
        XGBoostModel b = XGBoostModel.train(x, y, 20, 3, 0.3, 99);
        for (double[] row : x) assertEquals(a.predict(row), b.predict(row), 0.0);
    }

    @Test
    void contributionsSumToMarginDelta() {
        double[] y = new double[200];
        double[][] x = separable(y, 42);
        XGBoostModel m = XGBoostModel.train(x, y, 40, 4, 0.3, 7);

        double[] x0 = {0.0, 0.0};
        double[] x1 = {4.0, 4.0};
        double[] c0 = m.featureContributions(x0);
        double[] c1 = m.featureContributions(x1);
        assertEquals(2, c0.length);
        assertEquals(m.margin(x1) - m.margin(x0), (c1[0] + c1[1]) - (c0[0] + c0[1]), 1e-6);

        var contribs = m.contributions(x1, java.util.List.of("a", "b"));
        assertEquals(2, contribs.size());
        assertTrue(Math.abs(contribs.values().iterator().next()) >= Math.abs(contribs.get("b")));
    }
}