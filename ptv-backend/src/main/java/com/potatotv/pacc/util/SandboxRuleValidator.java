package com.potatotv.pacc.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v5.0 插件/模板运行时沙箱：白名单 Schema 校验（校验式沙箱）。
 * <p>Java 21 已弃用 SecurityManager，故不执行任意代码；而是在"应用插件规则/策略模板"
 * 入口对其 JSON 载荷做严格白名单校验：仅允许本 schema 声明的字段与取值，超范围一律拒绝，
 * 从而保证下发的处置项/插件规则无法注入恶意字段、恶意动作或超大负载。</p>
 */
public final class SandboxRuleValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_REF = new TypeReference<>() {
    };

    /** 允许的处置动作（对齐 DeterPolicy）。 */
    private static final Set<String> ACTIONS = Set.of("MONITOR", "BLOCK", "ISOLATE", "IGNORE");
    private static final Set<String> SCOPE_TYPES = Set.of("FAMILY", "SAMPLE", "SIGNATURE");

    private static final int MAX_ITEMS = 200;
    private static final int MAX_STRING = 512;
    private static final int SEVERITY_MIN = 1;
    private static final int SEVERITY_MAX = 5;

    private SandboxRuleValidator() {
    }

    /**
     * 校验动作数组 JSON。通过则返回规范化后的规则列表，否则抛 IllegalArgumentException。
     *
     * @param json 处置项 JSON 数组
     * @return 规范化后的规则列表（仅含允许字段）
     */
    public static List<Map<String, Object>> requireActions(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("actions 不允许为空");
        List<Map<String, Object>> rules;
        try {
            rules = MAPPER.readValue(json, LIST_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("actions 必须是合法 JSON 数组：" + e.getMessage());
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("actions 不能为空数组");
        if (rules.size() > MAX_ITEMS) throw new IllegalArgumentException("action 数量超限(>" + MAX_ITEMS + ")");
        return rules.stream().map(SandboxRuleValidator::validateOne).toList();
    }

    private static Map<String, Object> validateOne(Map<String, Object> rule) {
        for (String key : rule.keySet()) {
            if (!Set.of("scopeType", "scopeValue", "action", "severity", "note").contains(key)) {
                throw new IllegalArgumentException("非法字段: " + key);
            }
        }
        String scopeType = requireStr(rule, "scopeType");
        String scopeValue = requireStr(rule, "scopeValue");
        String action = requireStr(rule, "action");
        if (!SCOPE_TYPES.contains(scopeType)) throw new IllegalArgumentException("非法 scopeType: " + scopeType);
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("非法 action: " + action);
        if (scopeValue.length() > MAX_STRING) throw new IllegalArgumentException("scopeValue 过长");
        if (scopeValue.indexOf('\n') >= 0 || scopeValue.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("scopeValue 不允许含换行");
        }
        int severity = SEVERITY_MAX;
        Object sev = rule.get("severity");
        if (sev != null) {
            try {
                severity = ((Number) sev).intValue();
            } catch (Exception e) {
                throw new IllegalArgumentException("severity 必须为数字");
            }
            if (severity < SEVERITY_MIN || severity > SEVERITY_MAX) {
                throw new IllegalArgumentException("severity 须在 " + SEVERITY_MIN + ".." + SEVERITY_MAX);
            }
        }
        String note = rule.get("note") == null ? null : String.valueOf(rule.get("note"));
        if (note != null && note.length() > MAX_STRING) throw new IllegalArgumentException("note 过长");
        return Map.of("scopeType", scopeType, "scopeValue", scopeValue, "action", action,
                "severity", severity, "note", note);
    }

    private static String requireStr(Map<String, Object> rule, String key) {
        Object v = rule.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new IllegalArgumentException("缺少必填字段: " + key);
        return String.valueOf(v).trim();
    }
}