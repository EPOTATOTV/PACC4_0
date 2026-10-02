package com.potatotv.pacc.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开放 API 鉴权过滤器的放行边界：PTO 令牌端点（JWKS / 刷新）必须公开，
 * 其余 /api/v1、/api/dev 仍走 Key + HMAC。
 */
class ApiV1AuthFilterTest {

    private final ApiV1AuthFilter filter = new ApiV1AuthFilter(null, null, null, "master");

    @Test
    void ptoEndpointsSkipApiKeyAuth() {
        assertTrue(filter.shouldNotFilter(req("/api/v1/auth/jwks")));
        assertTrue(filter.shouldNotFilter(req("/api/v1/auth/refresh")));
    }

    @Test
    void openApiAndDevPortalStillGuarded() {
        assertFalse(filter.shouldNotFilter(req("/api/v1/detections")));
        assertFalse(filter.shouldNotFilter(req("/api/dev/portal")));
        // 玩家账号接口不在开放 API 前缀内，本过滤器不管
        assertTrue(filter.shouldNotFilter(req("/api/auth/login")));
    }

    private static MockHttpServletRequest req(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
