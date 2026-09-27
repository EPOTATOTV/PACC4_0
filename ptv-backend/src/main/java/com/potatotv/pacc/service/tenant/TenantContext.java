package com.potatotv.pacc.service.tenant;

/**
 * §4.2.3 租户上下文（ThreadLocal）：由 {@link TenantContextFilter} 依据认证主体解析并绑定，
 * 供业务层做租户级数据/配额守卫。
 *
 * <p><b>安全要点</b>：{@code X-Tenant-Id} 头绝不单独采信——只有认证主体确为该租户管理员
 * （或平台级身份且租户存在）时才绑定；解析失败或越权直接 403，不进入业务层。</p>
 */
public final class TenantContext {

    /**
     * @param actor    认证主体（管理员身份 / 密钥指纹）
     * @param role     认证角色（super-admin / operator / api-key / 租户角色）
     * @param tenantId 生效租户标识；平台级身份未指定时可为 null
     * @param platform 是否平台级身份（可跨租户）
     */
    public record Principal(String actor, String role, String tenantId, boolean platform) { }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前生效租户标识；未绑定返回 null。 */
    public static String tenantIdOrNull() {
        Principal p = HOLDER.get();
        return p == null ? null : p.tenantId();
    }

    public static boolean isPlatform() {
        Principal p = HOLDER.get();
        return p != null && p.platform();
    }
}