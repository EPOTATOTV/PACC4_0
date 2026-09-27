package com.potatotv.pacc.service.tenant;

import org.springframework.stereotype.Component;

/**
 * §4.2.3 租户守卫：显式校验「当前上下文是否有权访问目标租户」，为查询/写入提供统一的隔离断言。
 *
 * <p>这是验收 A25（租户 A 无法访问租户 B 数据）的强制执行点：任何跨租户访问都抛
 * {@link SecurityException}，绝不静默返回他人数据。</p>
 */
@Component
public class TenantGuard {

    /** 取当前生效租户；未绑定（如平台身份未指定租户）时抛错，避免无范围查询泄露全量数据。 */
    public String scopedTenant() {
        TenantContext.Principal p = TenantContext.get();
        if (p == null) {
            throw new SecurityException("缺少租户上下文");
        }
        if (p.tenantId() == null || p.tenantId().isBlank()) {
            throw new SecurityException("平台身份访问租户数据需显式指定租户（X-Tenant-Id）");
        }
        return p.tenantId();
    }

    /**
     * 校验对目标租户的访问权限并返回校验后的租户标识。
     *
     * @throws SecurityException 未认证 / 跨租户访问
     */
    public String requireAccess(String tenantId) {
        TenantContext.Principal p = TenantContext.get();
        if (p == null) {
            throw new SecurityException("缺少租户上下文");
        }
        if (p.platform()) {
            if (tenantId == null || tenantId.isBlank()) {
                return scopedTenant();
            }
            if (p.tenantId() != null && !p.tenantId().equals(tenantId)) {
                throw new SecurityException("跨租户访问被拒绝");
            }
            return tenantId;
        }
        if (tenantId == null || !tenantId.equals(p.tenantId())) {
            throw new SecurityException("跨租户访问被拒绝");
        }
        return tenantId;
    }

    /** 是否平台级身份。 */
    public boolean isPlatform() {
        return TenantContext.isPlatform();
    }

    /** 当前操作人。 */
    public String actor() {
        TenantContext.Principal p = TenantContext.get();
        return p == null ? "system" : p.actor();
    }
}