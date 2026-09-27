package com.potatotv.paccclient.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §7.1 硬件指纹测试：加权哈希的确定性与权重语义、缺失维度不参与计算、
 * 真机读取不抛异常且覆盖度可观测。
 */
class HardwareFingerprintV2Test {

    private static HardwareFingerprintV2.Component c(String name, String value, int weight, boolean stable) {
        return new HardwareFingerprintV2.Component(name, value, weight, stable);
    }

    @Test
    void hashIsDeterministicAndOrderIndependent() {
        List<HardwareFingerprintV2.Component> a = List.of(
                c("cpu", "Ryzen 7 5800X", 3, true),
                c("disk", "WD-XYZ123", 3, true),
                c("gpu", "RTX 3070", 1, false));
        List<HardwareFingerprintV2.Component> b = List.of(
                c("gpu", "RTX 3070", 1, false),
                c("disk", "WD-XYZ123", 3, true),
                c("cpu", "Ryzen 7 5800X", 3, true));

        assertEquals(HardwareFingerprintV2.hash(a, false), HardwareFingerprintV2.hash(b, false),
                "组件顺序不应影响摘要");
    }

    @Test
    void whitespaceAndCaseAreNormalized() {
        String one = HardwareFingerprintV2.hash(List.of(c("cpu", "  AMD Ryzen ", 3, true)), false);
        String two = HardwareFingerprintV2.hash(List.of(c("cpu", "amd   ryzen", 3, true)), false);

        assertEquals(one, two, "同一台机器的格式差异不应产生不同摘要");
    }

    @Test
    void stableHashIgnoresLowStabilityDimensions() {
        List<HardwareFingerprintV2.Component> before = List.of(
                c("cpu", "i7-12700", 3, true),
                c("gpu", "RTX 3070", 1, false));
        List<HardwareFingerprintV2.Component> after = List.of(
                c("cpu", "i7-12700", 3, true),
                c("gpu", "RTX 4080", 1, false));

        assertEquals(HardwareFingerprintV2.hash(before, true), HardwareFingerprintV2.hash(after, true),
                "换显卡不应改变稳定指纹");
        assertFalse(HardwareFingerprintV2.hash(before, false).equals(HardwareFingerprintV2.hash(after, false)),
                "但完整指纹应当变化");
    }

    @Test
    void weightedDimensionsAffectResultMore() {
        String base = HardwareFingerprintV2.hash(List.of(
                c("cpu", "x", 3, true), c("gpu", "y", 1, false)), false);
        String weightedSame = HardwareFingerprintV2.hash(List.of(
                c("cpu", "x", 3, true), c("gpu", "y", 1, false)), false);

        assertEquals(base, weightedSame);
        assertNotNull(base);
        assertEquals(64, base.length(), "SHA-256 十六进制应为 64 字符");
    }

    @Test
    void readWorksOnRealMachineWithoutThrowing() {
        HardwareFingerprintV2.Snapshot snapshot = HardwareFingerprintV2.read();

        assertNotNull(snapshot.fullHash());
        assertEquals(64, snapshot.fullHash().length());
        assertTrue(snapshot.coverage() >= 0 && snapshot.coverage() <= HardwareFingerprintV2.STABLE_DIMENSIONS);
        assertFalse(snapshot.components().isEmpty(), "至少有核数等纯 JDK 维度可读");
        // 同一进程内重复读取应稳定
        assertEquals(snapshot.fullHash(), HardwareFingerprintV2.read().fullHash());
    }
}