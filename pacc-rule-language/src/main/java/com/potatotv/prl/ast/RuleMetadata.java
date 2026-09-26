package com.potatotv.prl.ast;

import java.util.List;

/**
 * 规则元数据（设计文档 §2.2.1 规则头部）。
 *
 * <p>字段全部可缺省：{@code severity} 缺省为 {@link Severity#LOW}，{@code cooldown} 缺省为
 * 0（不冷却），{@code enabled} 缺省为 true，其余为 null。缺省值集中在
 * {@link #empty(int, int)} 里，解析器只填出现过的字段。</p>
 *
 * @param cooldownMillis 冷却时间，已按 §2.2.3 的单位规则归一化为毫秒
 */
public record RuleMetadata(String version,
                           String author,
                           Severity severity,
                           String category,
                           long cooldownMillis,
                           boolean enabled,
                           String description,
                           int line,
                           int col) implements AstNode {

    public static RuleMetadata empty(int line, int col) {
        return new RuleMetadata(null, null, Severity.LOW, null, 0L, true, null, line, col);
    }

    public RuleMetadata withVersion(String value, int atLine, int atCol) {
        return new RuleMetadata(value, author, severity, category, cooldownMillis, enabled, description, atLine, atCol);
    }

    public RuleMetadata withAuthor(String value, int atLine, int atCol) {
        return new RuleMetadata(version, value, severity, category, cooldownMillis, enabled, description, atLine, atCol);
    }

    public RuleMetadata withSeverity(Severity value, int atLine, int atCol) {
        return new RuleMetadata(version, author, value, category, cooldownMillis, enabled, description, atLine, atCol);
    }

    public RuleMetadata withCategory(String value, int atLine, int atCol) {
        return new RuleMetadata(version, author, severity, value, cooldownMillis, enabled, description, atLine, atCol);
    }

    public RuleMetadata withCooldownMillis(long value, int atLine, int atCol) {
        return new RuleMetadata(version, author, severity, category, value, enabled, description, atLine, atCol);
    }

    public RuleMetadata withEnabled(boolean value, int atLine, int atCol) {
        return new RuleMetadata(version, author, severity, category, cooldownMillis, value, description, atLine, atCol);
    }

    public RuleMetadata withDescription(String value, int atLine, int atCol) {
        return new RuleMetadata(version, author, severity, category, cooldownMillis, enabled, value, atLine, atCol);
    }
}