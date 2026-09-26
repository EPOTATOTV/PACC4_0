package com.potatotv.pcu;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * 重试工具（设计文档 §4.4：下载失败自动重试 3 次，指数退避）。
 *
 * <p>只对 IO 失败重试。校验不通过（{@link PcuException}）是确定性结果，重试没有意义，
 * 直接向上抛，让编排层走回滚。</p>
 */
final class Retry {

    private static final Logger LOG = Logger.getLogger(Retry.class.getName());

    /** 退避上限：避免重试次数被调大后退避到分钟级。 */
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);

    private Retry() {
    }

    @FunctionalInterface
    interface IoSupplier<T> {
        T get() throws IOException, InterruptedException;
    }

    static <T> T call(String what, int maxRetries, Duration baseDelay, IoSupplier<T> action) {
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long delayMs = backoffMillis(baseDelay, attempt);
                int nextAttempt = attempt + 1;
                LOG.warning(() -> what + " 第 " + nextAttempt + " 次尝试，等待 " + delayMs + "ms 后重试");
                sleep(delayMs);
            }
            try {
                return action.get();
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PcuException(what + "被中断", e);
            }
        }
        throw new PcuException(what + "失败：已重试 " + maxRetries + " 次", last);
    }

    private static long backoffMillis(Duration baseDelay, int attempt) {
        long base = Math.max(1L, baseDelay.toMillis());
        long shift = Math.min(attempt - 1, 20);
        long delay = base << shift;
        return Math.min(delay, MAX_DELAY.toMillis());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PcuException("重试等待被中断", e);
        }
    }
}