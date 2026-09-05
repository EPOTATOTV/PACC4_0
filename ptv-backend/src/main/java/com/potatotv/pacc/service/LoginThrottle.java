package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.AttemptLedger;
import org.springframework.stereotype.Component;

/**
 * 登录频率限制：对同作用域（账号 / 客户端 IP）在窗口内超过阈值即拒绝，
 * 缓解管理后台 / 玩家登录的暴力破解。
 * <p>存储经 {@link AttemptLedger} 抽象，默认进程内滑动窗口；多实例时以 Redis 实现交换，
 * 行为保持一致。窗口 60s、上限 5 次。</p>
 */
@Component
public class LoginThrottle {

    public static final long WINDOW_MS = 60_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final AttemptLedger ledger;

    public LoginThrottle(AttemptLedger ledger) {
        this.ledger = ledger;
    }

    /** 当前窗口内是否允许继续尝试。 */
    public boolean allowed(String key) {
        return ledger.size(key, System.currentTimeMillis()) < MAX_ATTEMPTS;
    }

    /** 记录一次失败尝试。 */
    public void hit(String key) {
        ledger.add(key, System.currentTimeMillis());
    }

    /** 登录成功后清除计数，避免用户被历史失败误伤。 */
    public void clear(String key) {
        ledger.clear(key);
    }
}