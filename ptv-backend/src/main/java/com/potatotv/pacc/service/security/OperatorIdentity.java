package com.potatotv.pacc.service.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * v5.4 管理端操作者身份小工具。
 *
 * <p>管理端认证由 {@code AdminKeyFilter} 统一处理：登录态会在请求属性里放 {@code adminActor}，
 * 而静态 api-key 直连则没有该属性。这里把两种来源归一，缺省用 {@code "api-key"}，
 * 让「谁改了摘要注册表」在审计日志里始终有值可查。保持 API 稳定是因为密钥管理等旁路服务也会复用。</p>
 */
public final class OperatorIdentity {

    private OperatorIdentity() {
    }

    /** 从请求属性取操作者身份；缺省为 api-key（静态密钥直连路径）。 */
    public static String of(HttpServletRequest req) {
        if (req == null) return "api-key";
        Object v = req.getAttribute("adminActor");
        if (v == null) return "api-key";
        String s = v.toString();
        return s.isBlank() ? "api-key" : s;
    }
}