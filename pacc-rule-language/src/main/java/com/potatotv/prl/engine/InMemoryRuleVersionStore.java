package com.potatotv.prl.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版版本库，给单测与本地开发用（设计文档 §2.13.1）。
 *
 * <p>不做持久化，重启即丢。管控后端应当实现 {@link RuleVersionStore} 落到 {@code prl_rule_versions}
 * 表上；这个实现的职责只是让发布流程在没有数据库的情况下也能被完整测试。</p>
 */
public final class InMemoryRuleVersionStore implements RuleVersionStore {

    /** 规则名 → （版本号 → 版本）；两层都是并发容器，读多写少，不额外加锁。 */
    private final Map<String, Map<String, RuleVersion>> byRule = new ConcurrentHashMap<>();

    @Override
    public void save(RuleVersion version) {
        byRule.computeIfAbsent(version.ruleName(), key -> new ConcurrentHashMap<>())
                .put(version.version(), version);
    }

    @Override
    public Optional<RuleVersion> find(String ruleName, String version) {
        Map<String, RuleVersion> versions = byRule.get(ruleName);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public Optional<RuleVersion> active(String ruleName) {
        return history(ruleName).stream().filter(v -> v.status() == RuleStatus.ACTIVE).findFirst();
    }

    @Override
    public List<RuleVersion> history(String ruleName) {
        Map<String, RuleVersion> versions = byRule.get(ruleName);
        if (versions == null) {
            return List.of();
        }
        List<RuleVersion> list = new ArrayList<>(versions.values());
        list.sort(Comparator.comparingLong(RuleVersion::createdAtMs).reversed()
                .thenComparing(RuleVersion::version));
        return list;
    }

    @Override
    public List<RuleVersion> all() {
        List<String> names = new ArrayList<>(byRule.keySet());
        names.sort(Comparator.naturalOrder());
        Map<String, RuleVersion> merged = new LinkedHashMap<>();
        for (String name : names) {
            for (RuleVersion version : history(name)) {
                merged.put(name + "@" + version.version(), version);
            }
        }
        return List.copyOf(merged.values());
    }
}