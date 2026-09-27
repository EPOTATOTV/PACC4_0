package com.potatotv.pacc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 管理员 2FA 的 pending 令牌测试：独立 issuer 防跨链路借用、角色夹带往返、越期/非法拒绝。
 */
class TotpServiceAdminPendingTest {

    private final TotpService svc = new TotpService(null, "test-secret-for-admin-pending-test-key");

    @Test
    void adminPendingRoundTrip() {
        String pending = svc.issueAdminPending("feishu:ou_user123", "super-admin");
        var ap = svc.parseAdminPending(pending);
        assertEquals("feishu:ou_user123", ap.identity());
        assertEquals("super-admin", ap.role());
    }

    @Test
    void playerPendingCannotPassAsAdminPending() {
        // 玩家链路签发的 pending，拿到管理员第二步端点必须被拒（独立 issuer）
        String playerPending = svc.issuePending("PT123456");
        assertThrows(SecurityException.class, () -> svc.parseAdminPending(playerPending));
    }

    @Test
    void adminPendingCannotPassAsPlayerPending() {
        String adminPending = svc.issueAdminPending("feishu:ou_user123", "operator");
        assertThrows(SecurityException.class, () -> svc.parsePending(adminPending));
    }

    @Test
    void garbagePendingRejected() {
        assertThrows(SecurityException.class, () -> svc.parseAdminPending("not-a-token"));
        assertThrows(SecurityException.class, () -> svc.parseAdminPending(null));
    }
}