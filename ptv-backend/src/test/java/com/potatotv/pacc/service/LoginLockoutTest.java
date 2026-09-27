package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.InMemoryAttemptLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录临时锁定单测：达到失败阈值即锁定、锁定期间不再累加、成功后清零、作用域互不干扰。
 * 纯进程内实现，延迟参数置 0 以免测试真的 sleep。
 */
class LoginLockoutTest {

    private static LoginLockout withThreshold(int maxFailures) {
        return new LoginLockout(new InMemoryAttemptLedger(LoginLockout.WINDOW_MS), maxFailures, 0, 0);
    }

    @Test
    void locksOnceFailuresReachThreshold() {
        LoginLockout lockout = withThreshold(10);
        assertFalse(lockout.isLocked("auth:player"));
        for (int i = 0; i < 9; i++) {
            lockout.recordFailure("auth:player");
        }
        assertFalse(lockout.isLocked("auth:player"));
        lockout.recordFailure("auth:player");
        assertTrue(lockout.isLocked("auth:player"));
    }

    @Test
    void lockedScopeStopsAccumulating() {
        InMemoryAttemptLedger ledger = new InMemoryAttemptLedger(LoginLockout.WINDOW_MS);
        LoginLockout lockout = new LoginLockout(ledger, 2, 0, 0);
        lockout.recordFailure("auth:victim");
        lockout.recordFailure("auth:victim");
        // 锁定后继续请求不得再累加：否则攻击者能把受害账号无限期锁死
        for (int i = 0; i < 50; i++) {
            lockout.recordFailure("auth:victim");
        }
        assertEquals(2, ledger.size("auth:victim", System.currentTimeMillis()));
    }

    @Test
    void resetClearsCounter() {
        LoginLockout lockout = withThreshold(3);
        lockout.recordFailure("auth:user");
        lockout.recordFailure("auth:user");
        lockout.reset("auth:user");
        lockout.recordFailure("auth:user");
        lockout.recordFailure("auth:user");
        assertFalse(lockout.isLocked("auth:user"));
    }

    @Test
    void scopesAreIndependent() {
        LoginLockout lockout = withThreshold(2);
        lockout.recordFailure("auth:a");
        lockout.recordFailure("auth:a");
        assertTrue(lockout.isLocked("auth:a"));
        assertFalse(lockout.isLocked("ip:203.0.113.7"));
    }
}
