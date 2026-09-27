package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.AttemptLedger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 账户 / 来源级临时锁定：在短窗频率限制（{@link LoginThrottle}，60s 内 5 次）之上，
 * 再叠一层长窗失败累计惩罚。同一作用域（账号、客户端 IP）在 {@link #WINDOW_MS} 内累计失败
 * 达到 {@code maxFailures} 次后，窗口内一律拒绝，直到最早的失败记录滑出窗口才自动解除。
 *
 * <p>为什么单靠短窗限流不够：它只约束「瞬时速率」，攻击者每 60 秒试 5 次即可长期慢速爆破
 * 弱口令账号（一小时 300 次）。长窗把单账号 / 单来源的尝试预算压到 15 分钟 10 次。</p>
 *
 * <p>锁定期间不再累计失败。若继续累加，攻击者只要持续发请求就能把受害账号无限期锁死，
 * 把防护本身变成拒绝服务。停止累加后，锁定自然到期，无需人工解锁。</p>
 *
 * <p>文案纪律：本服务只回答「是否锁定」，对外一律复用与凭据错误相同的通用提示，
 * 不区分账号是否存在、是否被锁定，避免账号枚举。</p>
 */
@Component
public class LoginLockout {

    /** 锁定窗口（同时是失败计数窗口）：15 分钟。 */
    public static final long WINDOW_MS = 15 * 60_000L;

    private final AttemptLedger ledger;
    private final int maxFailures;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public LoginLockout(@Qualifier("lockoutLedger") AttemptLedger ledger,
                        @Value("${pacc.auth.lockout.max-failures:10}") int maxFailures,
                        @Value("${pacc.auth.lockout.base-delay-ms:80}") long baseDelayMs,
                        @Value("${pacc.auth.lockout.max-delay-ms:400}") long maxDelayMs) {
        this.ledger = ledger;
        this.maxFailures = maxFailures;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** 该作用域当前是否处于锁定状态。 */
    public boolean isLocked(String scope) {
        return ledger.size(scope, System.currentTimeMillis()) >= maxFailures;
    }

    /** 记录一次凭据校验失败；已锁定时忽略，避免持续请求延长锁定。 */
    public void recordFailure(String scope) {
        long now = System.currentTimeMillis();
        if (ledger.size(scope, now) >= maxFailures) {
            return;
        }
        ledger.add(scope, now);
    }

    /** 登录成功后清空计数，避免历史失败误伤正常用户。 */
    public void reset(String scope) {
        ledger.clear(scope);
    }

    /**
     * 渐进式延迟：失败次数越多响应越慢，抬高自动化爆破的时间成本。
     * <p>上限受 {@code pacc.auth.lockout.max-delay-ms} 约束——延迟会占用请求线程，
     * 无上限的退避在洪峰下反而会耗尽线程池，把防护变成自我拒绝服务。</p>
     */
    public void applyProgressiveDelay(String scope) {
        if (baseDelayMs <= 0) {
            return;
        }
        int failures = ledger.size(scope, System.currentTimeMillis());
        if (failures <= 0) {
            return;
        }
        long delay = Math.min(baseDelayMs * failures, Math.max(baseDelayMs, maxDelayMs));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
