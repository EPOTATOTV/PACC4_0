package com.potatotv.paccclient.detection.stream;

import com.potatotv.paccclient.detection.FeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.1 增量特征累加器正确性测试：O(1) 增量更新必须与「取窗口内全部原始值两遍重算」在浮点容差内一致
 * ——这是把 100ms 周期全量重算替换为事件驱动增量计算的前提保证。
 */
class IncrementalFeatureAccumulatorTest {

    @Test
    void incrementalMeanAndVarianceMatchNaiveWindowRecomputation() {
        int window = 64;
        IncrementalFeatureAccumulator acc = new IncrementalFeatureAccumulator(window, 0.2);
        String key = FeatureSchema.keys().get(0);
        Random rng = new Random(7);
        List<Double> observed = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            double v = rng.nextGaussian() * 5.0 + 20.0;
            observed.add(v);
            acc.update(key, v);

            int from = Math.max(0, observed.size() - window);
            int n = observed.size() - from;
            double sum = 0;
            for (int j = from; j < observed.size(); j++) {
                sum += observed.get(j);
            }
            double mean = sum / n;
            double var = 0;
            for (int j = from; j < observed.size(); j++) {
                double d = observed.get(j) - mean;
                var += d * d;
            }
            var /= n;

            assertEquals(mean, acc.mean(key), Math.max(1e-9, Math.abs(mean) * 1e-9), "均值不一致 @" + i);
            assertEquals(var, acc.variance(key), Math.max(1e-9, Math.abs(var) * 1e-6), "方差不一致 @" + i);
            assertEquals(Math.sqrt(var), acc.std(key), Math.max(1e-9, Math.sqrt(var) * 1e-6), "标准差不一致 @" + i);
        }
        assertEquals(window, acc.count(key), "窗口样本数应恒等于窗口长度");
    }

    @Test
    void keyspaceIsBoundedToFeatureSchemaAndUnknownKeysIgnored() {
        IncrementalFeatureAccumulator acc = new IncrementalFeatureAccumulator(8, 0.2);
        acc.update("not_a_feature_key", 1.0);
        assertEquals(0, acc.trackedKeys(), "非 schema 键不得进入键空间");
        assertEquals(1L, acc.ignoredKeys());

        String key = FeatureSchema.keys().get(1);
        acc.observe(Map.of(key, 2.0));
        assertEquals(1, acc.trackedKeys());

        FeatureVector snapshot = acc.snapshot();
        assertEquals(2.0, snapshot.get(key), 1e-12);
        assertEquals(FeatureSchema.size(), snapshot.toArray().length, "快照仍按权威维度表取值");
    }

    @Test
    void observedStatisticsAreExposedForWindowedSignals() {
        IncrementalFeatureAccumulator acc = new IncrementalFeatureAccumulator(4, 0.5);
        String key = FeatureSchema.keys().get(0);
        for (double v : new double[]{2, 4, 6, 8, 10}) {
            acc.update(key, v);
        }
        assertEquals(4, acc.count(key), "窗口满后应淘汰最旧值");
        assertEquals(7.0, acc.mean(key), 1e-9, "(4+6+8+10)/4");
        assertEquals(28.0, acc.sum(key), 1e-9, "运行和随淘汰同步修正");
        assertEquals(10.0, acc.last(key), 1e-12);
        // min/max 为「自创建以来的极值」，不随窗口淘汰重算（诊断用途）
        assertEquals(2.0, acc.min(key), 1e-12);
        assertEquals(10.0, acc.max(key), 1e-12);
        assertTrue(acc.ewma(key) > 0.0 && acc.ewma(key) < 10.0);
    }
}