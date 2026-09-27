package com.potatotv.prl.bytecode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条规则的编译产物索引：规则名、入口函数下标、元数据（设计文档 §2.13.1 的版本库里要存的东西）。
 *
 * <p>元数据用字符串键的映射而不是强类型 record：{@code .prlc} 的 Metadata 段本来就是 JSON
 * （§2.8.1），宿主与数据库又都按字符串取值，中间再定义一层结构体只会多一次转换。</p>
 */
public record RuleEntry(String name, int functionIndex, Map<String, Object> metadata) {

    public RuleEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public String metadataString(String key, String fallback) {
        Object value = metadata.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean enabled() {
        Object value = metadata.get("enabled");
        return !(value instanceof Boolean bool) || bool;
    }

    public long cooldownMillis() {
        Object value = metadata.get("cooldown_ms");
        return value instanceof Number number ? number.longValue() : 0L;
    }
}