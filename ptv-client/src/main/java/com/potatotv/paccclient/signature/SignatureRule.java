package com.potatotv.paccclient.signature;

import java.util.Objects;

/**
 * 一条端侧检测规则（特征码）：由服务端特征库下发。
 * 不可变。risk 为风险等级 1-5。
 */
public final class SignatureRule {

    public final String id;
    public final String name;
    public final String pattern;
    public final int risk;
    public final long version;

    public SignatureRule(String id, String name, String pattern, int risk, long version) {
        this.id = id;
        this.name = name;
        this.pattern = pattern;
        this.risk = risk;
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignatureRule r)) return false;
        return risk == r.risk && version == r.version
                && id.equals(r.id) && name.equals(r.name) && pattern.equals(r.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, pattern, risk, version);
    }

    /** 摘要规范的序列化形式：id:pattern:risk:version（与服务端一致性校验口径一致）。 */
    public String canonical() {
        return id + ":" + pattern + ":" + risk + ":" + version;
    }
}