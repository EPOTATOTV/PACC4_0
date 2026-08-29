package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.LuaRuleEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态规则管理：规则清单 / 热更新 / 单样本试评。
 * 挂载于 /api/admin/**，由 AdminKeyFilter 统一鉴权。
 */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class RuleController {

    private final LuaRuleEngine ruleEngine;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "count", ruleEngine.list().size(),
                "rules", ruleEngine.list()));
    }

    /** 热更新：从 classpath rules/*.lua 重新加载。 */
    @PostMapping("/reload")
    public ResponseEntity<?> reload() {
        int n = ruleEngine.reload();
        return ResponseEntity.ok(Map.of("ok", true, "loaded", n));
    }

    /** 试评：对给定事件上下文执行规则引擎，返回命中明细与累计加分。 */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, Object> ctx) {
        LuaRuleEngine.Evaluation ev = ruleEngine.evaluate(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule_count", ev.ruleCount());
        out.put("bonus", ev.bonus());
        out.put("hits", ev.hits());
        return ResponseEntity.ok(out);
    }
}
