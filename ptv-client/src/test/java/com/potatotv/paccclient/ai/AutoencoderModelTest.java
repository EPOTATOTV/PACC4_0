package com.potatotv.paccclient.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoencoderModelTest {

    private static double[][] samples(int n, int d, long seed) {
        Random rng = new Random(seed);
        double[][] s = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) s[i][j] = rng.nextGaussian() * 0.3;
        }
        return s;
    }

    private static double meanError(double[][] s, AutoencoderModel m) {
        double sum = 0;
        for (double[] row : s) sum += m.reconstructionError(row);
        return sum / s.length;
    }

    @Test
    void fitReducesErrorAndFlagsOutlier() {
        double[][] s = samples(60, 4, 11);
        AutoencoderModel untrained = AutoencoderModel.fit(s, 6, 0, 0.05, 1);
        AutoencoderModel trained = AutoencoderModel.fit(s, 6, 400, 0.05, 1);

        double before = meanError(s, untrained);
        double after = meanError(s, trained);
        double outlier = trained.reconstructionError(new double[]{5, 5, 5, 5});

        assertTrue(after < before, "训练后训练集重构误差应下降: " + after + " < " + before);
        assertTrue(after < outlier, "正常样本误差应低于远离的离群样本: " + after + " < " + outlier);
    }

    @Test
    void serializeRoundTrip() {
        double[][] s = samples(40, 4, 3);
        AutoencoderModel m = AutoencoderModel.fit(s, 5, 100, 0.05, 2);
        AutoencoderModel back = AutoencoderModel.deserialize(m.serialize(), 4);
        assertEquals(4, back.featureDim());
        for (double[] row : s) {
            assertEquals(m.reconstructionError(row), back.reconstructionError(row), 1e-7);
        }
    }
}