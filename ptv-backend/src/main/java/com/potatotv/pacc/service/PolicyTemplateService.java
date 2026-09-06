package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DeterPolicy;
import com.potatotv.pacc.domain.PolicyTemplate;
import com.potatotv.pacc.repository.DeterPolicyRepository;
import com.potatotv.pacc.repository.PolicyTemplateRepository;
import com.potatotv.pacc.util.SandboxRuleValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v5.0 生态：策略模板管理，支持按场景预置策略并"一键应用"到 DeterPolicy。
 * <p>洞穿入口经 {@link SandboxRuleValidator} 白名单校验后，将动作项写为威慑策略记录，
 * 相同 scopeType+scopeValue 采用覆盖式，避免重复堆积。</p>
 */
@Service
@RequiredArgsConstructor
public class PolicyTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PolicyTemplateRepository templateRepo;
    private final DeterPolicyRepository policyRepo;

    public List<PolicyTemplate> list(String scene) {
        return (scene == null || scene.isBlank())
                ? templateRepo.findAllByOrderByCreatedAtDesc()
                : templateRepo.findBySceneOrderByCreatedAtDesc(scene);
    }

    /** 新建模板：actionsJson 经沙箱校验后存储规范化结果。 */
    public PolicyTemplate create(String name, String scene, String description, String actionsJson, String createdBy) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("template name is required");
        if (scene == null || scene.isBlank()) throw new IllegalArgumentException("scene is required");
        // 沙箱校验：失败即抛错，不落库
        String normalized = toJson(SandboxRuleValidator.requireActions(actionsJson));
        PolicyTemplate t = PolicyTemplate.builder()
                .templateId("tpl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .name(name.trim())
                .scene(scene.trim().toUpperCase())
                .description(description)
                .actionsJson(normalized)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();
        return templateRepo.save(t);
    }

    /** 一键应用：将模板动作项写入 DeterPolicy（同 scope 覆盖式）。返回生效条数。 */
    public Map<String, Object> apply(String templateId, String operator) {
        PolicyTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("template not found: " + templateId));
        List<Map<String, Object>> actions = SandboxRuleValidator.requireActions(t.getActionsJson());
        int applied = 0;
        for (Map<String, Object> a : actions) {
            String scopeType = (String) a.get("scopeType");
            String scopeValue = (String) a.get("scopeValue");
            String action = (String) a.get("action");
            int severity = (Integer) a.get("severity");
            String note = (String) a.get("note");
            DeterPolicy p = policyRepo.findByScopeTypeAndScopeValue(scopeType, scopeValue)
                    .orElse(DeterPolicy.builder()
                            .scopeType(scopeType).scopeValue(scopeValue).createdBy(operator)
                            .createdAt(Instant.now()).build());
            p.setAction(action);
            p.setSeverity(severity);
            p.setEnabled(true);
            p.setNote(note);
            policyRepo.save(p);
            applied++;
        }
        return Map.of("template_id", templateId, "scene", t.getScene(), "applied", applied);
    }

    public void delete(String templateId) {
        templateRepo.deleteById(templateId);
    }

    private String toJson(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("serialize actions failed", e);
        }
    }
}