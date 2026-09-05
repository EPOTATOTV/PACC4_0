package com.potatotv.pacc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v4.5 输入时序宏检测确定性单测：变异系数与宏判定、样本过少容错。
 */
class InputTimingAnalyzerTest {

    @Test
    void nullOrTooFewSamplesNotMacro() {
        assertFalse(InputTimingAnalyzer.analyze(null, 0.06).macro());
        assertFalse(InputTimingAnalyzer.analyze(List.of(150.0, 148.0), 0.06).macro());
        assertEquals(0, InputTimingAnalyzer.analyze(List.of(150.0), 0.06).score());
    }

    @Test
    void humanLikeJitterIsNotMacro() {
        // 人类点击：均值 ~150ms，CV ~0.4（明显抖动）
        List<Double> human = List.of(150.0, 160.0, 140.0, 220.0, 130.0, 170.0, 155.0, 90.0, 200.0, 145.0);
        var v = InputTimingAnalyzer.analyze(human, 0.06);
        assertFalse(v.macro());
        assertTrue(v.cv() > 0.06);
        // 非宏：人类正常输入，宏怀疑分为 0
        assertEquals(0, v.score());
    }

    @Test
    void fixedIntervalMacro() {
        // 宏：固定 100ms 间隔，CV≈0
        List<Double> macro = List.of(100.0, 100.0, 100.0, 100.0, 100.0, 100.0);
        var v = InputTimingAnalyzer.analyze(macro, 0.06);
        assertTrue(v.macro());
        assertEquals(0.0, v.cv(), 0.001);
        // 宏至少 70 分
        assertTrue(v.score() >= 70);
    }

    @Test
    void nearFixedIntervalMacro() {
        // 轻微抖动但 CV 仍低于阈值 → 判宏且高分会下降
        List<Double> near = List.of(100.0, 101.0, 99.0, 100.0, 100.0, 100.0);
        var v = InputTimingAnalyzer.analyze(near, 0.06);
        assertTrue(v.macro());
        assertTrue(v.score() < InputTimingAnalyzer.analyze(List.of(100.0, 100.0, 100.0, 100.0, 100.0, 100.0), 0.06).score());
    }
}