package com.potatotv.paccclient.detection.federated;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.2 隐私护栏测试：范数裁剪必须真正限住范数、非有限值必须被拒绝、样本数不足必须拒绝上传。
 */
class GradientPrivacyGuardTest {

    @Test
    void clipNormBoundsTheUploadedNorm() {
        double[] gradient = {100.0, 0.0, 0.0};
        double[] clipped = GradientPrivacyGuard.clipNorm(gradient, 5.0);

        assertEquals(5.0, GradientCodec.l2Norm(clipped), 1e-9, "裁剪后范数应等于上限");
        assertArrayEquals(new double[]{100.0, 0.0, 0.0}, gradient, "不得就地修改入参");
        // 方向不变：仅整体等比缩放
        assertEquals(1.0, clipped[0] / GradientCodec.l2Norm(clipped), 1e-9);
    }

    @Test
    void clipNormLeavesWithinCapGradientUntouched() {
        double[] gradient = {1.0, 2.0, 2.0};
        assertArrayEquals(gradient, GradientPrivacyGuard.clipNorm(gradient, 10.0), 1e-12);
    }

    @Test
    void nonFiniteGradientIsRejected() {
        GradientPrivacyGuard guard = new GradientPrivacyGuard(50.0, 20);

        GradientPrivacyGuard.GuardResult nan = guard.inspect(new double[]{1.0, Double.NaN}, 100);
        assertFalse(nan.allowed());
        assertTrue(nan.reason().contains("非有限值"), "拒绝原因应指明非有限值，实际：" + nan.reason());

        GradientPrivacyGuard.GuardResult inf = guard.inspect(new double[]{Double.POSITIVE_INFINITY}, 100);
        assertFalse(inf.allowed());
    }

    @Test
    void lowSampleCountIsRefused() {
        GradientPrivacyGuard guard = new GradientPrivacyGuard(50.0, 20);

        assertFalse(guard.inspect(new double[]{1.0, 2.0}, 19).allowed(), "样本数低于门限不得上传");
        assertTrue(guard.inspect(new double[]{1.0, 2.0}, 20).allowed(), "达到门限应放行");
    }

    @Test
    void emptyGradientIsRejected() {
        GradientPrivacyGuard guard = new GradientPrivacyGuard(50.0, 1);
        assertFalse(guard.inspect(new double[0], 100).allowed());
    }
}