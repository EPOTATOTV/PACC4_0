package com.potatotv.pacc.util.ml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * v4.6 孤立森林确定性单测：离群点独离分数显著高于簇内点，且分数落在 (0,1]。
 */
class IsolationForestTest {

    private static double[][] cluster(double base, int count, double spread) {
        double[][] m = new double[count][2];
        for (int i = 0; i < count; i++) {
            m[i][0] = base + spread * Math.sin(i * 1.7);
            m[i][1] = base + spread * Math.cos(i * 1.3);
        }
        return m;
    }

    @Test
    void inlierScoreIsLowAndOutlierHigh() {
        double[][] cluster = cluster(5.0, 60, 0.2);
        double[][] forestData = new double[60 + 6][2];
        System.arraycopy(cluster, 0, forestData, 0, 60);
        // 加入少量正常抖动样本作为训练集
        for (int i = 0; i < 6; i++) {
            forestData[60 + i][0] = 5.0 + 0.15 * Math.cos(i);
            forestData[60 + i][1] = 5.0 + 0.15 * Math.sin(i);
        }

        IsolationForest forest = new IsolationForest(forestData, 40, 16, 8, 42L);

        // 簇内点
        double inlier = forest.anomalyScore(new double[]{5.1, 5.0});
        // 离群点（远离簇）
        double outlier = forest.anomalyScore(new double[]{50.0, -3.0});

        assertTrue(outlier > inlier, "outlier=" + outlier + " should exceed inlier=" + inlier);
        assertTrue(inlier >= 0.0 && inlier <= 1.0);
        assertTrue(outlier > 0.0 && outlier <= 1.0);
    }

    @Test
    void extremePointIsolatesBeyondInlier() {
        double[][] cluster = cluster(1.0, 40, 0.1);
        double[][] forestData = new double[40 + 4][2];
        System.arraycopy(cluster, 0, forestData, 0, 40);
        for (int i = 0; i < 4; i++) {
            forestData[40 + i][0] = 1.0 + 0.1 * Math.sin(i);
            forestData[40 + i][1] = 1.0 + 0.1 * Math.cos(i);
        }
        IsolationForest forest = new IsolationForest(forestData, 50, 20, 8, 7L);
        double inlier = forest.anomalyScore(new double[]{1.05, 0.98});
        double extreme = forest.anomalyScore(new double[]{100.0, 100.0});
        assertTrue(extreme > inlier, "extreme=" + extreme + " should exceed inlier=" + inlier);
        assertTrue(extreme > 0.5, "extreme point should be noticeably isolated, got " + extreme);
    }
}