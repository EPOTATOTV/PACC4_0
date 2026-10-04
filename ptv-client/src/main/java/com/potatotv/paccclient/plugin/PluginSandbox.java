package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 插件检测器沙箱（文档 §2.3 约束 3、4）：执行超时 + 熔断。
 *
 * <p>插件注册的每个 {@link Detector} 都被包一层：{@link #detect} 在独立线程执行，超过
 * {@value #DEFAULT_TIMEOUT_MS}ms 视为超时，取消并记一次失败；连续失败 {@value #MAX_FAILURES} 次
 * 进入熔断，冷却 {@value #COOLDOWN_MS}ms 内直接跳过该检测器，冷却后先调 {@code onRecover()} 再试。
 * 插件异常与超时都不会向上冒泡，宿主调度器永远拿到一个「合法的空结果」。</p>
 *
 * <p><b>能力边界（必须诚实说明）</b>：</p>
 * <ul>
 *   <li>看门狗线程只能 {@code interrupt} 插件任务；插件若吞掉中断继续跑，线程不会被强杀。
 *       因此这是「隔离调度」而非「强制终止」。</li>
 *   <li>文档 §2.3 的 CPU 5% / 内存 64MB 是<em>软预算</em>：Java 21 无 SecurityManager，
 *       进程内无法给单线程/单插件配 CPU 配额，也无法可靠计量单个插件的堆占用。
 *       本实现用「执行时长上限 + 熔断」逼近 CPU 预算目标；内存预算依赖插件签名可信 + 进程整体
 *       {@code -Xmx}。不做假装有效的硬限制。</li>
 * </ul>
 */
public final class PluginSandbox implements AutoCloseable {

    /** 单次 {@code detect()} 超时（ms），文档 §2.3 约束 4。 */
    public static final long DEFAULT_TIMEOUT_MS = 100;
    /** 连续失败达到该次数进入熔断。 */
    static final int MAX_FAILURES = 3;
    /** 熔断冷却时长（ms）。 */
    static final long COOLDOWN_MS = 60_000;

    private final long timeoutMs;
    private final ExecutorService executor;
    private final Map<String, FailureState> states = new ConcurrentHashMap<>();

    public PluginSandbox() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /** 测试与调优接缝：自定义超时时长。 */
    public PluginSandbox(long timeoutMs) {
        this.timeoutMs = Math.max(1, timeoutMs);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ptv-plugin-sandbox");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    /** 把一个插件检测器包成受沙箱约束的检测器。 */
    public Detector wrap(String pluginId, Detector delegate) {
        return new SandboxedDetector(pluginId, delegate);
    }

    /** 某检测器当前是否处于熔断状态（供诊断 / 测试）。 */
    public boolean isCircuitOpen(String detectorId) {
        FailureState st = states.get(detectorId);
        return st != null && st.openUntil > System.currentTimeMillis();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void recordFailure(String detectorId, FailureState st) {
        st.failures++;
        if (st.failures >= MAX_FAILURES) {
            st.openUntil = System.currentTimeMillis() + COOLDOWN_MS;
            System.err.println("[PTV-Plugin] 检测器熔断 detector=" + detectorId
                    + " 连续失败=" + st.failures);
        }
    }

    /** 失败计数与熔断截止时间。 */
    private static final class FailureState {
        int failures;
        long openUntil;
    }

    private final class SandboxedDetector implements Detector {

        private final String pluginId;
        private final Detector delegate;

        SandboxedDetector(String pluginId, Detector delegate) {
            this.pluginId = pluginId;
            this.delegate = delegate;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public long intervalMs() {
            return delegate.intervalMs();
        }

        @Override
        public Optional<DetectionEvent> detect(DetectContext ctx) {
            FailureState st = states.computeIfAbsent(id(), k -> new FailureState());
            long now = System.currentTimeMillis();
            if (st.openUntil > now) {
                return Optional.empty();
            }
            if (st.openUntil != 0) {
                // 冷却结束：先给插件一次恢复机会，再正常调用
                st.openUntil = 0;
                st.failures = 0;
                try {
                    delegate.onRecover();
                } catch (RuntimeException e) {
                    System.err.println("[PTV-Plugin] onRecover 异常 plugin=" + pluginId + ": " + e);
                }
            }

            Future<Optional<DetectionEvent>> future = executor.submit(() -> delegate.detect(ctx));
            try {
                Optional<DetectionEvent> result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                st.failures = 0;
                return result == null ? Optional.empty() : result;
            } catch (TimeoutException e) {
                future.cancel(true);
                recordFailure(id(), st);
                return Optional.empty();
            } catch (ExecutionException e) {
                recordFailure(id(), st);
                return Optional.empty();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                recordFailure(id(), st);
                return Optional.empty();
            }
        }

        @Override
        public void onRecover() {
            // 恢复由沙箱在冷却结束时统一触发，这里不再转发，避免重复调用
        }
    }
}