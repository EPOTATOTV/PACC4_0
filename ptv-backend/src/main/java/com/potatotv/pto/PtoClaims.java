package com.potatotv.pto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 校验通过的 JWT 载荷只读视图。
 * <p>取值不会抛异常：缺失或类型不符一律走安全兜底（字符串返回 null、时间返回 0），
 * 是否需要「claim 必须存在」由调用方自行判断。</p>
 */
public final class PtoClaims {

    /** 设备指纹 claim 名：绑定签发时的设备，令牌被挪到其它设备使用时应被拒绝。 */
    public static final String DFP = "dfp";

    /** 令牌用途 claim 名：access / refresh。 */
    public static final String TYPE = "type";

    private final Map<String, Object> claims;

    PtoClaims(Map<String, Object> claims) {
        this.claims = Collections.unmodifiableMap(claims);
    }

    public String subject() {
        return getString("sub");
    }

    public String issuer() {
        return getString("iss");
    }

    public String id() {
        return getString("jti");
    }

    /** 绑定的设备指纹；未绑定时返回 null。 */
    public String deviceFingerprint() {
        return getString(DFP);
    }

    /** 令牌用途（access / refresh）；未声明时返回 null。 */
    public String type() {
        return getString(TYPE);
    }

    /** 过期时间（epoch 秒）。缺失返回 0。 */
    public long expiresAt() {
        return asLong(claims.get("exp"));
    }

    /** 签发时间（epoch 秒）。缺失返回 0。 */
    public long issuedAt() {
        return asLong(claims.get("iat"));
    }

    /** 生效时间（epoch 秒）。缺失返回 0。 */
    public long notBefore() {
        return asLong(claims.get("nbf"));
    }

    /** 受众。兼容 jjwt 的数组写法与自定义实现的字符串写法。 */
    public List<String> audience() {
        Object v = claims.get("aud");
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return List.copyOf(out);
        }
        return List.of(String.valueOf(v));
    }

    public String getString(String key) {
        Object v = claims.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Object get(String key) {
        return claims.get(key);
    }

    public boolean has(String key) {
        return claims.containsKey(key);
    }

    /** 全部 claim 的只读视图。 */
    public Map<String, Object> asMap() {
        return claims;
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
