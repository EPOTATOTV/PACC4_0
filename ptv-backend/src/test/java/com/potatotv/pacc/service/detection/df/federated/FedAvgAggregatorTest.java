package com.potatotv.pacc.service.detection.df.federated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DF §4.1.2 联邦学习验收测试。
 *
 * <p><b>A24 证据</b>：{@link #fedAvgConvergesOnConvexObjectiveOverHundredRounds()} 以 3 个合成客户端、
 * 100 轮 FedAvg 训练一个小规模凸二次目标（{@code f_i(w) = ½‖w − b_i‖²}，Hessian = I），
 * 断言损失逐轮单调不增、且 100 轮后收敛到阈值以下。客户端只上传梯度（模型增量），
 * 服务端从不接触任何原始数据——这正是「原始数据不出设备」的可验证形态。</p>
 *
 * <p>其余用例覆盖 FedAvg 的样本数加权、隐私/形状校验与参数更新公式。</p>
 */
class FedAvgAggregatorTest {

    /** 合成客户端数量（不少于 3）。 */
    private static final int CLIENTS = 3;
    /** 训练轮次（不少于 100）。 */
    private static final int ROUNDS = 100;
    /** 特征维度。 */
    private static final int DIM = 4;
    /** 学习率：A = I ⇒ L = 1，取 lr = 0.4 < 2/L 保证单调收敛。 */
    private static final double LEARNING_RATE = 0.4;

    /** A24：100 轮 FedAvg 后损失收敛，且逐轮单调不增。 */
    @Test
    void fedAvgConvergesOnConvexObjectiveOverHundredRounds() {
        // 各端本地数据不同（本地最优解 b_i 不同），样本数也不同 → 触发样本数加权
        double[][] localTargets = {
                {2.0, 0.0, 1.0, 0.0},
                {0.0, 2.0, 1.0, 3.0},
                {1.0, 1.0, 3.0, 0.0},
        };
        int[] sampleCounts = {50, 30, 20};
        long totalSamples = 0;
        for (int n : sampleCounts) {
            totalSamples += n;
        }
        double[] globalTarget = weightedTarget(localTargets, sampleCounts, totalSamples);

        double[] weights = new double[DIM];
        List<Double> losses = new ArrayList<>(ROUNDS);
        List<Double> reportedLosses = new ArrayList<>(ROUNDS);

        for (int round = 0; round < ROUNDS; round++) {
            List<ClientGradient> uploads = new ArrayList<>(CLIENTS);
            for (int c = 0; c < CLIENTS; c++) {
                // 本地一步全量梯度（原始数据不出设备，只上传这个向量）
                double[] gradient = new double[DIM];
                double localLoss = 0;
                for (int i = 0; i < DIM; i++) {
                    double residual = weights[i] - localTargets[c][i];
                    gradient[i] = residual;
                    localLoss += 0.5 * residual * residual;
                }
                uploads.add(new ClientGradient("client-" + c, gradient, sampleCounts[c], localLoss));
            }

            FedAvgAggregator.Aggregate agg = FedAvgAggregator.aggregate(uploads);
            assertEquals(totalSamples, agg.totalSamples());
            assertEquals(CLIENTS, agg.clients());

            // 聚合梯度应等于全局目标在 w 处的梯度：w − b̄
            double[] expectedGlobalGradient = new double[DIM];
            for (int i = 0; i < DIM; i++) {
                expectedGlobalGradient[i] = weights[i] - globalTarget[i];
            }
            assertArrayEquals(expectedGlobalGradient, agg.gradient(), 1e-12);

            weights = FedAvgAggregator.applyGradient(weights, agg.gradient(), LEARNING_RATE);
            losses.add(globalLoss(weights, globalTarget));
            reportedLosses.add(agg.avgLoss());
        }

        double first = losses.get(0);
        double last = losses.get(ROUNDS - 1);
        double firstTenAvg = average(losses, 0, 10);
        double lastTenAvg = average(losses, ROUNDS - 10, ROUNDS);

        // 真实观测值（报告里引用这一行输出）
        System.out.printf("[A24] clients=%d rounds=%d loss[0]=%.6e loss[99]=%.6e "
                        + "first10avg=%.6e last10avg=%.6e reportedLoss[0]=%.6e reportedLoss[99]=%.6e%n",
                CLIENTS, ROUNDS, first, last, firstTenAvg, lastTenAvg,
                reportedLosses.get(0), reportedLosses.get(ROUNDS - 1));

        for (int i = 1; i < losses.size(); i++) {
            assertTrue(losses.get(i) <= losses.get(i - 1) + 1e-12,
                    "第 " + i + " 轮损失回升：" + losses.get(i) + " > " + losses.get(i - 1));
        }
        assertTrue(lastTenAvg < firstTenAvg, "末 10 轮均值应低于首 10 轮均值");
        assertTrue(last < 1e-6, "A24 失败：100 轮后损失 " + last + " 未收敛到 1e-6 以下");
        assertTrue(reportedLosses.get(ROUNDS - 1) <= reportedLosses.get(0), "客户端上报损失亦应下降");
    }

    /** 聚合按样本数加权，而非等权平均。 */
    @Test
    void aggregateWeightsBySampleCount() {
        ClientGradient small = new ClientGradient("a", new double[]{1.0, 1.0}, 1, 0.5);
        ClientGradient large = new ClientGradient("b", new double[]{3.0, 3.0}, 3, 1.0);

        FedAvgAggregator.Aggregate agg = FedAvgAggregator.aggregate(List.of(small, large));
        assertArrayEquals(new double[]{2.5, 2.5}, agg.gradient(), 1e-12);
        assertEquals(4L, agg.totalSamples());
        assertEquals(2, agg.clients());
        assertEquals(0.875, agg.avgLoss(), 1e-12);
    }

    /** 空批量或样本数非正时必须拒绝，而不是产出无意义的聚合。 */
    @Test
    void aggregateRejectsEmptyOrInvalidSampleCount() {
        assertThrows(IllegalArgumentException.class, () -> FedAvgAggregator.aggregate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> FedAvgAggregator.aggregate(
                List.of(new ClientGradient("a", new double[]{1.0}, 0, null))));
        assertThrows(IllegalArgumentException.class, () -> FedAvgAggregator.aggregate(
                List.of(new ClientGradient("a", new double[]{1.0}, 1, null),
                        new ClientGradient("b", new double[]{1.0, 2.0}, 1, null))));
    }

    /** 未上报损失的客户端不参与损失加权（不会把 null 当 0 拉低均值）。 */
    @Test
    void aggregateSkipsMissingLoss() {
        ClientGradient withLoss = new ClientGradient("a", new double[]{2.0}, 1, 0.4);
        ClientGradient without = new ClientGradient("b", new double[]{4.0}, 1, null);

        FedAvgAggregator.Aggregate agg = FedAvgAggregator.aggregate(List.of(withLoss, without));
        assertArrayEquals(new double[]{3.0}, agg.gradient(), 1e-12);
        assertEquals(0.4, agg.avgLoss(), 1e-12);
    }

    /** 隐私/形状校验：拒绝非有限值（NaN/Inf）与空向量。 */
    @Test
    void validateRejectsNonFiniteAndEmpty() {
        assertFalse(FedAvgAggregator.validate(new double[]{0.1, Double.NaN}, 0, 4096, 50).valid());
        assertFalse(FedAvgAggregator.validate(new double[]{Double.POSITIVE_INFINITY}, 0, 4096, 50).valid());
        assertFalse(FedAvgAggregator.validate(new double[0], 0, 4096, 50).valid());
    }

    /** 隐私校验：拒绝离群范数与维度不一致/超大载荷。 */
    @Test
    void validateRejectsOutlierNormAndBadDimension() {
        assertFalse(FedAvgAggregator.validate(new double[]{100, 100}, 0, 4096, 50).valid());
        assertFalse(FedAvgAggregator.validate(new double[]{1, 2, 3}, 2, 4096, 50).valid());
        assertFalse(FedAvgAggregator.validate(new double[5000], 0, 4096, 50).valid());
    }

    /** 合规梯度通过校验。 */
    @Test
    void validateAcceptsReasonableGradient() {
        FedAvgAggregator.Validation ok = FedAvgAggregator.validate(new double[]{0.1, 0.2}, 2, 4096, 50);
        assertTrue(ok.valid());
        assertEquals("", ok.reason());
    }

    /** 参数更新公式 w' = w − lr·g；维度变更时从零向量重建。 */
    @Test
    void applyGradientFollowsFederatedSgdRule() {
        assertArrayEquals(new double[]{0.8, 1.8},
                FedAvgAggregator.applyGradient(new double[]{1.0, 2.0}, new double[]{0.5, 0.5}, 0.4), 1e-12);
        assertArrayEquals(new double[]{8.5, 8.5},
                FedAvgAggregator.applyGradient(new double[]{9.0, 9.0}, new double[]{0.5, 0.5}, 1.0), 1e-12);
        // 维度变化 → 忽略旧参数，从零开始
        assertArrayEquals(new double[]{-1.0, -1.0, -1.0},
                FedAvgAggregator.applyGradient(new double[]{5.0}, new double[]{1.0, 1.0, 1.0}, 1.0), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> FedAvgAggregator.applyGradient(new double[]{1.0}, new double[]{1.0}, 0.0));
    }

    /** 编解码往返无损：持久化后再读出的向量与内存中逐位一致。 */
    @Test
    void codecRoundTripIsLossless() {
        double[] vector = {0.1, -3.25, 1e-9, 12345.678, 0.0};
        assertArrayEquals(vector, GradientCodec.decode(GradientCodec.encode(vector)), 0.0);
        assertEquals(64, GradientCodec.sha256Hex(vector).length());
        assertThrows(IllegalArgumentException.class, () -> GradientCodec.decode("abc"));
    }

    // ------------------------------ 辅助 ------------------------------

    private static double[] weightedTarget(double[][] localTargets, int[] sampleCounts, long totalSamples) {
        double[] target = new double[DIM];
        for (int c = 0; c < localTargets.length; c++) {
            for (int i = 0; i < DIM; i++) {
                target[i] += localTargets[c][i] * sampleCounts[c];
            }
        }
        for (int i = 0; i < DIM; i++) {
            target[i] /= totalSamples;
        }
        return target;
    }

    private static double globalLoss(double[] weights, double[] target) {
        double loss = 0;
        for (int i = 0; i < weights.length; i++) {
            double residual = weights[i] - target[i];
            loss += 0.5 * residual * residual;
        }
        return loss;
    }

    private static double average(List<Double> values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += values.get(i);
        }
        return sum / (to - from);
    }
}