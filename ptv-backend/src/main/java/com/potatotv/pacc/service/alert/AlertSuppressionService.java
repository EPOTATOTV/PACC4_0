package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.domain.alert.AlertSuppressionRule;
import com.potatotv.pacc.repository.AlertSuppressionRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §4.3.2 误报抑制：已知误报模式（familyCode / ruleId / ruleName / metric 的包含匹配）命中即抑制，
 * 被抑制的信号不进入聚合与通知队列；命中计数留痕，供降噪率统计。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储 null 分析误报
public class AlertSuppressionService {

    private final AlertSuppressionRuleRepository repository;

    /** 判断信号是否命中任一启用的抑制规则；命中即累加计数并返回 true。 */
    @Transactional
    public boolean isSuppressed(AlertSignal signal) {
        for (AlertSuppressionRule rule : repository.findByEnabledTrue()) {
            if (matches(rule, signal)) {
                rule.setHitCount(rule.getHitCount() + 1);
                rule.setLastHitAt(Instant.now());
                repository.save(rule);
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<AlertSuppressionRule> list() {
        return repository.findAll();
    }

    @Transactional
    public AlertSuppressionRule create(String name, String pattern, String familyCode, String reason, String createdBy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("抑制规则名称不能为空");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("抑制匹配模式不能为空");
        }
        return repository.save(AlertSuppressionRule.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .name(name.trim())
                .pattern(pattern.trim())
                .familyCode(familyCode)
                .reason(reason)
                .enabled(true)
                .createdBy(createdBy)
                .build());
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("抑制规则不存在: " + id);
        }
        repository.deleteById(id);
    }

    /** 匹配：家族码（若限定）必须一致，且模式命中家族码/规则 id/规则名/指标之一。 */
    boolean matches(AlertSuppressionRule rule, AlertSignal signal) {
        if (rule.getFamilyCode() != null && !rule.getFamilyCode().isBlank()
                && !rule.getFamilyCode().equalsIgnoreCase(signal.getFamilyCode())) {
            return false;
        }
        String pattern = rule.getPattern().toLowerCase();
        return contains(signal.getFamilyCode(), pattern)
                || contains(signal.getRuleId(), pattern)
                || contains(signal.getRuleName(), pattern)
                || contains(signal.getMetric(), pattern);
    }

    private static boolean contains(String value, String lowerPattern) {
        return value != null && value.toLowerCase().contains(lowerPattern);
    }
}