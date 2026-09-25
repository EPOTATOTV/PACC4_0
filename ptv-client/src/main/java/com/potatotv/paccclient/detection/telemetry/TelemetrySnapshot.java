package com.potatotv.paccclient.detection.telemetry;

import java.util.Map;
import java.util.Set;

/**
 * 遥测快照：维度值 + 「确实探测过」的键集合。
 *
 * <p>用于计算 {@code FeatureCollector.coverage()}（文档验收 A02：真实数据维度数）——
 * 只有真正执行过底层读取的键才计入覆盖，纯 JDK 无法获取的键（如 SSDT 钩子）值为 0 且不计入，
 * 由平台层 / Java Agent 后续回填。</p>
 *
 * @param values 维度值
 * @param backed 已真实探测的键
 */
public record TelemetrySnapshot(Map<String, Double> values, Set<String> backed) {

    /** 空快照（探测整体失败时的降级结果）。 */
    public static TelemetrySnapshot empty() {
        return new TelemetrySnapshot(Map.of(), Set.of());
    }
}