package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.SystemProbes;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginSandbox} 超时与熔断测试（文档 §2.3 约束 3、4）。 */
class PluginSandboxTest {

    private static DetectContext ctx() {
        return new DetectContext(SystemProbes.create(), new FeatureVector());
    }

    private static Detector detector(String id, long interval,
                                     Function<DetectContext, Optional<DetectionEvent>> body) {
        return new Detector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public long intervalMs() {
                return interval;
            }

            @Override
            public Optional<DetectionEvent> detect(DetectContext context) {
                return body.apply(context);
            }
        };
    }

    @Test
    void passesThroughNormalResult() {
        try (PluginSandbox sandbox = new PluginSandbox()) {
            DetectionEvent event = new DetectionEvent("test", "low", 1);
            Detector sandboxed = sandbox.wrap("p", detector("d1", 1000, c -> Optional.of(event)));
            assertEquals(Optional.of(event), sandboxed.detect(ctx()));
        }
    }

    @Test
    void timeoutReturnsEmptyWithoutOpeningCircuit() {
        try (PluginSandbox sandbox = new PluginSandbox(50)) {
            Detector slow = detector("slow", 1000, c -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(new DetectionEvent("x", "low", 1));
            });
            assertTrue(sandbox.wrap("p", slow).detect(ctx()).isEmpty());
            assertFalse(sandbox.isCircuitOpen("slow"));
        }
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        try (PluginSandbox sandbox = new PluginSandbox(50)) {
            AtomicInteger calls = new AtomicInteger();
            Detector failing = detector("fail", 1000, c -> {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            });
            Detector sandboxed = sandbox.wrap("p", failing);
            for (int i = 0; i < PluginSandbox.MAX_FAILURES; i++) {
                assertTrue(sandboxed.detect(ctx()).isEmpty());
            }
            assertTrue(sandbox.isCircuitOpen("fail"));
            int before = calls.get();
            assertTrue(sandboxed.detect(ctx()).isEmpty());
            assertEquals(before, calls.get(), "熔断中不应再调用插件");
        }
    }

    @Test
    void metadataDelegates() {
        try (PluginSandbox sandbox = new PluginSandbox()) {
            Detector sandboxed = sandbox.wrap("p", detector("meta", 5000, c -> Optional.empty()));
            assertEquals("meta", sandboxed.id());
            assertEquals(5000, sandboxed.intervalMs());
        }
    }
}