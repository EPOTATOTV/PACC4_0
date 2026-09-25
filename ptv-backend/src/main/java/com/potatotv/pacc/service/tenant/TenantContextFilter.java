package com.potatotv.pacc.service.tenant;

import com.potatotv.pacc.domain.TenantAdmin;
import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.repository.TenantAdminRepository;
import com.potatotv.pacc.repository.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * §4.2.3 租户上下文过滤器：从认证主体（{@code AdminKeyFilter} 注入的 adminActor/adminRole）
 * 解析出生效租户并绑定到 {@link TenantContext}；请求结束务必清理 ThreadLocal。
 *
 * <p>解析规则（安全优先）：
 * <ul>
 *   <li>平台级身份（super-admin/operator/api-key）：可经 {@code X-Tenant-Id} 切换，但该租户必须真实存在；</li>
 *   <li>租户级身份：{@code X-Tenant-Id} 必须命中其「已绑定且启用」的租户，否则 403；
 *       无该头时若仅绑定一个租户则自动选取，否则不绑定（后续访问会被 {@link TenantGuard} 拒绝）；</li>
 *   <li>本过滤器注册在 Spring Security 过滤器链之后，因此可安全读取认证属性。</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Tenant-Id";

    private final TenantAdminRepository tenantAdminRepository;
    private final TenantRepository tenantRepository;
    private final DfProperties props;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            TenantContext.set(resolve(request));
        } catch (SecurityException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private TenantContext.Principal resolve(HttpServletRequest request) {
        String actor = (String) request.getAttribute("adminActor");
        String role = (String) request.getAttribute("adminRole");
        if (actor == null) {
            // 未经认证（理论上被 AdminKeyFilter 拦截）；给一个无范围上下文，业务层守卫会拒绝租户访问
            return new TenantContext.Principal("anonymous", role == null ? "anonymous" : role, null, false);
        }
        boolean platform = role != null && props.getTenant().getPlatformRoles().contains(role);
        String header = trimToNull(request.getHeader(HEADER));
        if (platform) {
            String tenant = header;
            if (tenant != null && !tenantRepository.existsById(tenant)) {
                throw new SecurityException("租户不存在: " + tenant);
            }
            return new TenantContext.Principal(actor, role, tenant, true);
        }
        // 租户级身份：校验 X-Tenant-Id 是否为该身份绑定的启用租户
        List<TenantAdmin> bindings = tenantAdminRepository.findByAdminIdentity(actor).stream()
                .filter(TenantAdmin::isEnabled).toList();
        if (header != null) {
            boolean bound = bindings.stream().anyMatch(b -> header.equals(b.getTenantId()));
            if (!bound) {
                throw new SecurityException("无权切换到租户: " + header);
            }
            return new TenantContext.Principal(actor, role, header, false);
        }
        List<String> ids = bindings.stream().map(TenantAdmin::getTenantId).distinct().toList();
        String auto = ids.size() == 1 ? ids.get(0) : null;
        return new TenantContext.Principal(actor, role, auto, false);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}