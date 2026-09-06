package com.potatotv.pacc.service.detection.v47;

import com.potatotv.pacc.domain.DeterPolicy;
import com.potatotv.pacc.repository.DeterPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v4.7 主动威慑：对已确认恶意样本 / 家族 / 特征配置分级处置策略（MONITOR 观测 / BLOCK 阻断 /
 * ISOLATE 隔离 / IGNORE 放行），供事件风控在命中时按策略联动，形成"威胁情报确认 → 运营处置"的威慑闭环。
 */
@Service
@RequiredArgsConstructor
public class DeterrenceService {

    public static final String[] ACTIONS = {"MONITOR", "BLOCK", "ISOLATE", "IGNORE"};

    private final DeterPolicyRepository repository;

    /** 新增或覆盖某作用域的分级处置策略。 */
    public DeterPolicy setPolicy(String scopeType, String scopeValue, String action,
                                 Integer severity, String note, String operator) {
        if (!"FAMILY".equalsIgnoreCase(scopeType) && !"SAMPLE".equalsIgnoreCase(scopeType)
                && !"SIGNATURE".equalsIgnoreCase(scopeType)) {
            throw new IllegalArgumentException("scopeType 仅支持 FAMILY / SAMPLE / SIGNATURE");
        }
        String act = action == null ? "MONITOR" : action.toUpperCase(java.util.Locale.ROOT);
        boolean known = false;
        for (String a : ACTIONS) if (a.equals(act)) known = true;
        if (!known) throw new IllegalArgumentException("action 仅支持 MONITOR / BLOCK / ISOLATE / IGNORE");

        String st = scopeType.toUpperCase(java.util.Locale.ROOT);
        DeterPolicy policy = repository.findByScopeTypeAndScopeValue(st, scopeValue)
                .orElseGet(() -> DeterPolicy.builder()
                        .scopeType(st).scopeValue(scopeValue).createdAt(Instant.now()).build());
        policy.setAction(act);
        if (severity != null) policy.setSeverity(Math.max(1, Math.min(5, severity)));
        policy.setEnabled(true);
        policy.setNote(note);
        policy.setCreatedBy(operator);
        return repository.save(policy);
    }

    /** 启用/停用策略。 */
    public DeterPolicy setEnabled(Long id, boolean enabled) {
        DeterPolicy p = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("policy not found: " + id));
        p.setEnabled(enabled);
        return repository.save(p);
    }

    /** 当前生效的策略 + 按动作统计。 */
    public Map<String, Object> overview() {
        List<DeterPolicy> active = repository.findAllByEnabledTrueOrderByCreatedAtDesc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policies", active);
        out.put("active_count", repository.countByEnabledTrue());
        out.put("by_action", Map.of(
                "MONITOR", repository.countByAction("MONITOR"),
                "BLOCK", repository.countByAction("BLOCK"),
                "ISOLATE", repository.countByAction("ISOLATE"),
                "IGNORE", repository.countByAction("IGNORE")));
        return out;
    }

    /** 事件风控联动：给定命中家族，解析应执行的处置动作（未配置默认 MONITOR 观测）。 */
    public Map<String, Object> resolve(String family) {
        int severity = 3;
        DeterPolicy best = null;
        if (family != null && !family.isBlank()) {
            best = repository.findByScopeTypeAndScopeValue("FAMILY", family).orElse(null);
        }
        String action = best == null ? "MONITOR" : best.getAction();
        if (best != null) severity = best.getSeverity();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("action", action);
        out.put("severity", severity);
        out.put("protected", best != null && best.isEnabled());
        return out;
    }

    public List<DeterPolicy> policies() {
        return repository.findAllByEnabledTrueOrderByCreatedAtDesc();
    }
}