package com.potatotv.prl.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次告警。
 *
 * <p>来自 §2.4.7 的 {@code emit_alert}，也是 §2.12.2 里 {@code RuleManager.executeAll} 收集并返回的元素。
 * 规则里 {@code emit_alert(...)} 的返回值就是这个类型，但规则通常只把它当语句用，真正消费它的是宿主。</p>
 *
 * @param ruleName    产生告警的规则名，由 {@code RuleManager} 在收集时补上；脱离宿主单独执行时为空串
 * @param type        告警类型，规则自己命名，如 {@code "KILL_AURA"}
 * @param confidence  置信度，0~1
 * @param evidence    证据键值对，内容由规则组装
 * @param timestampMs 产生时间（毫秒时间戳）
 */
public record DetectionResult(String ruleName,
                              String type,
                              double confidence,
                              Map<Object, Object> evidence,
                              long timestampMs) {

    public DetectionResult {
        // 不用 Map.copyOf：它会拒绝 null 键/值，而规则组装的 evidence 里出现 null 是合法的
        evidence = evidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    public DetectionResult withRuleName(String name) {
        return new DetectionResult(name, type, confidence, evidence, timestampMs);
    }

    /** 转成宿主 API 常用的字符串键映射。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rule_name", ruleName);
        map.put("type", type);
        map.put("confidence", confidence);
        map.put("evidence", evidence);
        map.put("timestamp_ms", timestampMs);
        return map;
    }

    @Override
    public String toString() {
        return "DetectionResult[" + ruleName + " " + type + " " + confidence + " " + evidence + "]";
    }
}