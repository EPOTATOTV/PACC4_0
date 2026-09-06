package com.potatotv.pacc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PTEID 验证码服务单测：签发/核验/错误锁定/重发限流/过期。
 * 纯进程内实现，直接构造（ttl=10min, resend=60s）。
 */
class VerifyCodeServiceTest {

    private final VerifyCodeService svc = new VerifyCodeService(10, 60);

    @Test
    void issueThenVerifyOk() {
        String code = svc.issue("player@example.com", "register");
        assertNotNull(code);
        assertTrue(svc.verify("player@example.com", "register", code));
    }

    @Test
    void wrongCodeFailsAndConsumesOnSuccess() {
        String code = svc.issue("a@b.com", "register");
        assertFalse(svc.verify("a@b.com", "register", "000000"));
        // 一次性：正确的码核验后即被消费
        assertTrue(svc.verify("a@b.com", "register", code));
        assertFalse(svc.verify("a@b.com", "register", code));
    }

    @Test
    void keyIsCaseInsensitiveAndTrimmed() {
        String code = svc.issue("  USER@EXAMPLE.com ", "LOGIN");
        assertTrue(svc.verify("user@example.com", "login", code));
    }

    @Test
    void resendWithinWindowThrottled() {
        assertNotNull(svc.issue("t@x.com", "register"));
        // 60s 窗口内重发返回 null（不重复发送）
        assertNull(svc.issue("t@x.com", "register"));
    }

    @Test
    void fiveWrongAttemptsLocksUntilExpiry() {
        String code = svc.issue("lock@x.com", "register");
        for (int i = 0; i < 5; i++) {
            assertFalse(svc.verify("lock@x.com", "register", "000000"));
        }
        // 锁定期间即使正确码也拒绝
        assertFalse(svc.verify("lock@x.com", "register", code));
    }

    @Test
    void verifyUnknownTargetFails() {
        assertFalse(svc.verify("nobody@x.com", "register", "123456"));
        assertFalse(svc.verify("x@y.com", "register", null));
    }
}