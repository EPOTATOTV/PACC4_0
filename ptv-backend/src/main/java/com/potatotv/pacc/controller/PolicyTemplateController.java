package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.PolicyTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5.0 生态：场景策略模板管理 + 一键应用（受 X-Admin-Key 保护）。
 * <p>模板创建与应用均经沙箱白名单校验，防止注入非法处置动作。</p>
 */
@RestController
@RequestMapping("/api/admin/policy-templates")
@RequiredArgsConstructor
public class PolicyTemplateController {

    private final PolicyTemplateService templates;

    @GetMapping
    public Object list(@RequestParam(required = false) String scene) {
        return templates.list(scene);
    }

    /** 新建策略模板（actionsJson 经沙箱校验后存储规范化结果）。 */
    @PostMapping
    public Object create(@RequestBody Map<String, String> body) {
        return templates.create(body.get("name"), body.get("scene"), body.get("description"),
                body.get("actionsJson"), body.get("createdBy"));
    }

    /** 一键应用：将模板动作写入 DeterPolicy。 */
    @PostMapping("/{id}/apply")
    public Object apply(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? "system" : body.getOrDefault("operator", "system");
        return templates.apply(id, operator);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        templates.delete(id);
        return Map.of("deleted", id);
    }
}