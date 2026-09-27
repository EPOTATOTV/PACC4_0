package com.potatotv.paccclient.detection.federated;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.2 梯度上传器测试：低样本客户端不得上传、非有限梯度被拒、上传载荷中的梯度必须先被裁剪、
 * 传输失败只计数不抛出（fail-safe）。
 */
class GradientUploaderTest {

    private static long num(Map<String, Object> stats, String key) {
        return ((Number) stats.get(key)).longValue();
    }

    private static void awaitAtLeast(List<?> list, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (list.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void lowSampleCountClientDoesNotUpload() throws Exception {
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        GradientUploader uploader = new GradientUploader("PT0000000001",
                new GradientPrivacyGuard(50.0, 20), received::add, 16, 1L, 2);
        uploader.start();
        try {
            assertFalse(uploader.submit("r1", new double[]{1.0, 2.0, 3.0}, 5, 0.4));
            awaitAtLeast(received, 1, 120);
            assertTrue(received.isEmpty(), "样本数不足时不得发出任何载荷");
            assertEquals(1L, num(uploader.stats(), "rejected"));
            assertEquals(0L, num(uploader.stats(), "sent"));
        } finally {
            uploader.close();
        }
    }

    @Test
    void nonFiniteGradientIsRejectedBeforeQueueing() throws Exception {
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        GradientUploader uploader = new GradientUploader("PT0000000001",
                new GradientPrivacyGuard(50.0, 1), received::add, 16, 1L, 2);
        uploader.start();
        try {
            assertFalse(uploader.submit("r1", new double[]{1.0, Double.NaN}, 100, 0.1));
            awaitAtLeast(received, 1, 120);
            assertTrue(received.isEmpty());
            assertEquals(1L, num(uploader.stats(), "rejected"));
        } finally {
            uploader.close();
        }
    }

    @Test
    void hugeGradientIsClippedBeforeUpload() throws Exception {
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        GradientUploader uploader = new GradientUploader("PT0000000001",
                new GradientPrivacyGuard(2.0, 1), received::add, 16, 1L, 2);
        uploader.start();
        try {
            assertTrue(uploader.submit("r1", new double[]{100.0, 0.0, 0.0}, 100, 0.9));
            awaitAtLeast(received, 1, 1000);

            assertEquals(1, received.size());
            Map<String, Object> payload = received.get(0);
            double[] onWire = GradientCodec.decode((String) payload.get("gradient"));
            assertTrue(GradientCodec.l2Norm(onWire) <= 2.0 + 1e-9,
                    "上线梯度范数必须被裁剪到上限内，实际 " + GradientCodec.l2Norm(onWire));
            assertEquals("PT0000000001", payload.get("clientId"));
            assertEquals(100, ((Number) payload.get("sampleCount")).intValue());
            assertEquals(3, ((Number) payload.get("featureDim")).intValue());
            assertEquals("r1", payload.get("roundId"));
            assertEquals(1L, num(uploader.stats(), "sent"));
        } finally {
            uploader.close();
        }
    }

    @Test
    void transportFailureIsCountedNotThrown() throws Exception {
        GradientUploader uploader = new GradientUploader("PT0000000001", new GradientPrivacyGuard(50.0, 1),
                payload -> {
                    throw new IllegalStateException("通道不可用");
                }, 16, 0L, 1);
        uploader.start();
        try {
            assertTrue(uploader.submit("r1", new double[]{1.0}, 100, 0.2));
            long deadline = System.currentTimeMillis() + 1000;
            while (num(uploader.stats(), "failed") == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1L, num(uploader.stats(), "failed"), "发送失败应被计数而非抛出");
        } finally {
            uploader.close();
        }
    }

    @Test
    void settingsFactoryWiresConfiguredPrivacyCapAndQueue() throws Exception {
        FederatedSettings settings = FederatedSettings.defaults();
        assertEquals(50.0, settings.privacyGuard().maxNorm(), 1e-12);
        assertEquals(20, settings.privacyGuard().minSamples());

        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        GradientUploader uploader = settings.uploader("PT0000000001", received::add);
        uploader.start();
        try {
            assertFalse(uploader.submit("r1", new double[]{100.0, 0.0, 0.0}, 10, 0.3), "样本数低于配置门限不得上传");
            assertTrue(uploader.submit("r1", new double[]{100.0, 0.0, 0.0}, 30, 0.3));
            awaitAtLeast(received, 1, 1000);

            double[] onWire = GradientCodec.decode((String) received.get(0).get("gradient"));
            assertTrue(GradientCodec.l2Norm(onWire) <= settings.maxGradientNorm() + 1e-9,
                    "上线梯度范数应被配置上限裁剪，实际 " + GradientCodec.l2Norm(onWire));
        } finally {
            uploader.close();
        }
    }
}