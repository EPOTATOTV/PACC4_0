package com.potatotv.paccclient.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.2 检测引擎接线测试：确认「没有真实数据就不产生事件」（旧 {@code Math.random()} 演示路径已移除）、
 * 真实输入驱动能产出事件、特征覆盖度可观测。
 *
 * <p>Java Agent 探针指向未监听端口（连接被立即拒绝），隐身探针关闭以保持测试只验证 L0/L1 链路。</p>
 */
class DetectionEngineTest {

    private boolean stealthWasEnabled;

    @BeforeEach
    void setUp() {
        stealthWasEnabled = PerfToggles.enabled(PerfToggles.STEALTH_PROBES);
        PerfToggles.set(PerfToggles.STEALTH_PROBES, false);
    }

    @AfterEach
    void tearDown() {
        PerfToggles.set(PerfToggles.STEALTH_PROBES, stealthWasEnabled);
    }

    private static DetectionEngine engine() {
        return new DetectionEngine(null, new JavaAgentProbe("http://127.0.0.1:1"));
    }

    @Test
    void idleSamplingNeverProducesRandomEvents() {
        DetectionEngine engine = engine();
        for (int i = 0; i < 30; i++) {
            assertTrue(engine.sample(58).isEmpty(), "无输入数据时不得产生任何事件（第 " + i + " 次）");
        }
        assertEquals(LayeredDecision.Decision.PERIODIC, engine.lastDecision());
    }

    @Test
    void realClickBurstTriggersAutoclickerEvent() {
        DetectionEngine engine = engine();
        long base = System.currentTimeMillis() - 1000;
        // 1000ms 内 30 次点击（间隔 30ms，CPS≈30）：远超 L0 阈值 14
        for (int i = 0; i < 30; i++) {
            engine.inputSource().pushInput(InputEvent.click(base + i * 30L, 20.0));
        }

        Optional<DetectionEvent> event = engine.sample(58);

        assertTrue(event.isPresent(), "真实高频点击应触发事件");
        assertEquals("autoclicker", event.get().eventType());
        assertTrue(event.get().clientRiskScore() >= 45);
        assertEquals(LayeredDecision.Decision.LOCAL_BLOCK, engine.lastDecision());
    }

    @Test
    void trajectoryWithSnapAndTargetAttractionIsFlagged() {
        DetectionEngine engine = engine();
        // 直线瞬移轨迹 + 目标吸引：拟合误差高、瞬移占比高
        for (int i = 0; i < 12; i++) {
            engine.inputSource().pushTrajectory(new Point2D(i * 25.0, 0.0));
        }
        engine.inputSource().setAimTarget(new Point2D(300.0, 0.0));

        Optional<DetectionEvent> event = engine.sample(58);

        assertTrue(event.isPresent(), "直线瞬移 + 目标吸引应命中轨迹层");
        assertTrue(event.get().clientRiskScore() > 0);
    }

    @Test
    void coverageReflectsRealTelemetry() {
        DetectionEngine engine = engine();

        engine.sample(58);

        assertTrue(engine.lastCoverage() > 0, "环境/JVM/网络遥测应提供真实维度");
    }

    @Test
    void agentProbeOfflineReturnsEmptyFindings() {
        JavaAgentProbe probe = new JavaAgentProbe("http://127.0.0.1:1");
        assertFalse(probe.online(), "未监听端口不应被判定为在线");
        assertTrue(probe.poll(new BufferedInputSource()).isEmpty());
        assertTrue(probe.scanForMods(false).isEmpty());
        assertEquals("java_mod", probe.scanForMods(true).orElseThrow().eventType());
    }
}