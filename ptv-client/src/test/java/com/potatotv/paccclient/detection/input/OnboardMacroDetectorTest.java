package com.potatotv.paccclient.detection.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link OnboardMacroDetector} 多维打分测试（文档 §3.5）。 */
class OnboardMacroDetectorTest {

    @Test
    void 多信号叠加达到高分() {
        int score = OnboardMacroDetector.score(true, true, true, true, 0.5, 0.9);

        assertTrue(score >= 85, "设备 + 软件 + 固定间隔 + 爆发 + 抖动 + 同步应 ≥85，实际=" + score);
    }

    @Test
    void 仅设备线索不触发() {
        int score = OnboardMacroDetector.score(true, false, false, false, -1.0, 0.0);

        assertEquals(15, score, "单凭设备只有 15 分，不触发");
    }
}