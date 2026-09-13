package com.potatotv.pacc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * v4.8/v5.0 注册管理端操作审计拦截器与 RBAC 方法级权限拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuditInterceptor adminAuditInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    public WebMvcConfig(AdminAuditInterceptor adminAuditInterceptor, PermissionInterceptor permissionInterceptor) {
        this.adminAuditInterceptor = adminAuditInterceptor;
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(adminAuditInterceptor).addPathPatterns("/api/admin/**");
        // RBAC 方法级权限判定（annotation 驱动）：未标注 @RequirePermission 的方法自动放行
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/admin/**");
    }
}