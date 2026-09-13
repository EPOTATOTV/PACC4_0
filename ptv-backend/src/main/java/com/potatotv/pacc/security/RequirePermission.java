package com.potatotv.pacc.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级权限要求，值形如 {@code module:operation}（如 {@code alerts:update}）。
 * 由 {@link com.potatotv.pacc.config.PermissionInterceptor} 依据 adminRole 的权限矩阵判定；
 * 未标注的方法保持放行（additive，不强闯）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}