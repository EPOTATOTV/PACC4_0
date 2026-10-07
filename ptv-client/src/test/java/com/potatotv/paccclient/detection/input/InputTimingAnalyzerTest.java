package com.potatotv.paccclient.detection.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.samples.InputEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** {@link InputTimingAnalyzer} 纯分析测试。 */
class InputTimingAnalyzerTest {

    @Test
    void 固定间隔点击判定为固定间隔() {
        List<InputEvent> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(InputEvent.click(1_000L + i * 100L));
        }

        InputTimingAnalyzer.TimingStats s = InputTimingAnalyzer.analyze(events, 300);

        assertTrue(s.fixedInterval());
        assertTrue(s.clickJitterMs() < 1.0, "固定 100ms 抖动应很小，实际=" + s.clickJitterMs());
        assertEquals(40, s.clickCount());
    }

    @Test
    void 随机间隔不判定为固定间隔() {
        Random r = new Random(7);
        List<InputEvent> events = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 40; i++) {
            t += 50 + r.nextInt(400);
            events.add(InputEvent.click(t));
        }

        InputTimingAnalyzer.TimingStats s = InputTimingAnalyzer.analyze(events, 300);

        assertFalse(s.fixedInterval());
    }

    @Test
    void 快快慢序列判定为爆发模式() {
        List<InputEvent> events = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 15; i++) {
            t += 200;
            events.add(InputEvent.click(t));
            t += 20;
            events.add(InputEvent.click(t));
            t += 20;
            events.add(InputEvent.click(t));
        }

        InputTimingAnalyzer.TimingStats s = InputTimingAnalyzer.analyze(events, 300);

        assertTrue(s.burstPattern(), "快-快-慢序列应判定爆发模式");
    }

    @Test
    void 键鼠配对事件同步率高() {
        List<InputEvent> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t = 1_000L + i * 100L;
            events.add(InputEvent.click(t));
            events.add(new InputEvent(t + 2, InputEvent.Kind.KEY, 0.0));
        }

        InputTimingAnalyzer.TimingStats s = InputTimingAnalyzer.analyze(events, 300);

        assertTrue(s.keyClickSync() > 0.8, "配对按键同步率应 >0.8，实际=" + s.keyClickSync());
    }
}