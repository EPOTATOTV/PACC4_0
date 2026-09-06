package com.potatotv.pacc.util.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v4.6 指纹聚类单测：token 化确定性、词袋向量、K-Means 分组可分离。
 */
class FingerprintClustererTest {

    @Test
    void tokenizeIsDeterministicAndLowercased() {
        List<String> t1 = FingerprintClusterer.tokenize(Map.of("java_ghost_client", "com.ghost.GhostClient"));
        List<String> t2 = FingerprintClusterer.tokenize(Map.of("java_ghost_client", "com.ghost.GhostClient"));
        assertEquals(t1, t2);
        assertTrue(t1.contains("ghost"));
    }

    @Test
    void vectorOfBagsTokensIntoBuckets() {
        double[] v = FingerprintClusterer.vectorOf(List.of("ghost", "inject", "ghost"), 48);
        double len = 0;
        for (double x : v) len += x;
        assertEquals(3.0, len, 1e-9);
    }

    @Test
    void kmeansSeparatesDistinctGroups() {
        // 两组明显分离的二维点
        List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(new double[]{1 + i * 0.05, 1 + i * 0.05});
        for (int i = 0; i < 6; i++) rows.add(new double[]{8 + i * 0.05, 8 + i * 0.05});

        int[] labels = FingerprintClusterer.kmeans(rows, 2, 200, 3L);
        assertEquals(12, labels.length);

        // 前 6 个同类、后 6 个同类，两组不同
        for (int i = 1; i < 6; i++) assertEquals(labels[0], labels[i]);
        for (int i = 7; i < 12; i++) assertEquals(labels[6], labels[i]);
        assertNotEquals(labels[0], labels[6]);
    }

    @Test
    void kmeansDeterministicWithSameSeed() {
        List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(new double[]{i, i});
        int[] a = FingerprintClusterer.kmeans(rows, 2, 100, 42L);
        int[] b = FingerprintClusterer.kmeans(rows, 2, 100, 42L);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i]);
    }
}