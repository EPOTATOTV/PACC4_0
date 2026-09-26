package com.potatotv.paccclient.detection.federated;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * DF §4.1.2 端侧梯度上传器：把本地梯度<b>排队 + 重试</b>地交给既有传输通道发送，
 * 绝不阻塞检测热路径（{@link #submit} 只做校验 + 入队 + 唤醒）。
 *
 * <p>传输由调用方注入（生产环境是 {@code OpsClient#submitFederatedUpdate}，即玩家侧
 * {@code /api/player/df/federated/updates} 的 HTTP POST），本类不感知 HTTP 细节。
 * 契约字段与云端 {@code DfFederatedPlayerController#submitUpdate} 的请求体对齐：
 * {@code roundId / clientId / sampleCount / gradient（逗号分隔浮点串）/ loss}；{@code gradient} 用
 * 逗号串而非 JSON 数组，既与云端 {@code gradientOf} 的字符串分支兼容，也避免在客户端 JSON 编码器里
 * 引入数组支持。{@code featureDim} / {@code gradientNorm} 是端侧诊断冗余字段，服务端按 Map 取键，
 * 多余键被忽略。</p>
 *
 * <p>隐私：原始事件数据与特征值一律不进入载荷；客户端标识只带一个（{@code clientId}，默认取设备
 * PTEID）。发送前先过 {@link GradientPrivacyGuard}（拒绝非有限值、范数裁剪、样本数门限）。</p>
 *
 * <p>失败语义：单条发送异常按 {@code retryDelayMillis} 退避重试，超过 {@code maxAttempts} 记失败并丢弃；
 * 队列有界（{@code maxQueued}），满时丢弃最旧。任务线程为守护线程，任何异常都不会传播到调用方。</p>
 */
public final class GradientUploader implements AutoCloseable {

    private final String clientId;
    private final GradientPrivacyGuard guard;
    private final Consumer<Map<String, Object>> transport;
    private final int maxQueued;
    private final long retryDelayMillis;
    private final int maxAttempts;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Deque<Attempt> queue = new ArrayDeque<>();

    private volatile boolean running;
    private Thread worker;

    private long queued;
    private long sent;
    private long failed;
    private long rejected;
    private long dropped;
    private long retried;

    /**
     * @param clientId         客户端标识（最小化：仅传一个匿名设备/玩家 id）
     * @param guard            隐私护栏
     * @param transport        传输落点（生产为 {@code OpsClient#submitFederatedUpdate}）
     * @param maxQueued        待发队列上限
     * @param retryDelayMillis 失败重试间隔（毫秒）
     * @param maxAttempts      单条最大尝试次数（含首次）
     */
    public GradientUploader(String clientId, GradientPrivacyGuard guard, Consumer<Map<String, Object>> transport,
                            int maxQueued, long retryDelayMillis, int maxAttempts) {
        this.clientId = clientId == null ? "" : clientId;
        this.guard = guard;
        this.transport = transport;
        this.maxQueued = Math.max(1, maxQueued);
        this.retryDelayMillis = Math.max(0L, retryDelayMillis);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** 以默认队列/重试参数构造。 */
    public GradientUploader(String clientId, GradientPrivacyGuard guard, Consumer<Map<String, Object>> transport) {
        this(clientId, guard, transport, 16, 5000L, 3);
    }

    /**
     * 提交一份本地梯度：先过隐私护栏（拒绝非有限值/样本不足），再按范数上限裁剪，最后入队。
     *
     * @param roundId     轮次 id（可为空，服务端取当前开放轮次）
     * @param gradient    本地梯度
     * @param sampleCount 参与训练的样本数
     * @param loss        本地损失（可为 {@code null}）
     * @return 已入队返回 {@code true}；被护栏拒绝返回 {@code false}
     */
    public boolean submit(String roundId, double[] gradient, int sampleCount, Double loss) {
        try {
            GradientPrivacyGuard.GuardResult r = guard.inspect(gradient, sampleCount);
            if (!r.allowed()) {
                rejected++;
                return false;
            }
            double[] clipped = GradientPrivacyGuard.clipNorm(gradient, guard.maxNorm());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "df_federated_update");
            payload.put("roundId", roundId == null ? "" : roundId);
            payload.put("clientId", clientId);
            payload.put("sampleCount", sampleCount);
            payload.put("gradient", GradientCodec.encode(clipped));
            payload.put("featureDim", clipped.length);
            payload.put("gradientNorm", GradientCodec.l2Norm(clipped));
            payload.put("loss", loss == null || !Double.isFinite(loss) ? 0.0 : loss);

            lock.lock();
            try {
                if (queue.size() >= maxQueued) {
                    queue.pollFirst();
                    dropped++;
                }
                queue.addLast(new Attempt(payload));
                queued++;
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
            return true;
        } catch (RuntimeException e) {
            // fail-safe：隐私/入队环节异常绝不影响检测链路
            failed++;
            return false;
        }
    }

    /** 启动发送工作线程（无待发项时阻塞）。重复调用无副作用。 */
    public void start() {
        synchronized (this) {
            if (worker != null) {
                return;
            }
            running = true;
            worker = Thread.ofPlatform().daemon().name("pacc-df-federated-upload").start(this::runLoop);
        }
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = worker;
            worker = null;
        }
        lock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        if (t != null) {
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 上传器运行统计。 */
    public Map<String, Object> stats() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("queued", queued);
            m.put("pending", queue.size());
            m.put("sent", sent);
            m.put("retried", retried);
            m.put("failed", failed);
            m.put("rejected", rejected);
            m.put("dropped", dropped);
            return m;
        } finally {
            lock.unlock();
        }
    }

    private void runLoop() {
        while (running) {
            Attempt attempt;
            lock.lock();
            try {
                while (running && queue.isEmpty()) {
                    notEmpty.await();
                }
                if (!running) {
                    return;
                }
                attempt = queue.pollFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            if (attempt == null) {
                continue;
            }
            try {
                transport.accept(attempt.payload);
                sent++;
            } catch (RuntimeException e) {
                handleFailure(attempt);
            }
        }
    }

    /** 发送失败：未超上限则置回队首并退避重试，超限记失败丢弃。 */
    private void handleFailure(Attempt attempt) {
        boolean retry = attempt.attempts + 1 < maxAttempts;
        lock.lock();
        try {
            if (retry) {
                queue.addFirst(new Attempt(attempt.payload, attempt.attempts + 1));
                retried++;
                notEmpty.signal();
            } else {
                failed++;
            }
        } finally {
            lock.unlock();
        }
        if (retry && retryDelayMillis > 0) {
            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 队列元素：载荷 + 已尝试次数。 */
    private static final class Attempt {
        final Map<String, Object> payload;
        final int attempts;

        Attempt(Map<String, Object> payload) {
            this(payload, 0);
        }

        Attempt(Map<String, Object> payload, int attempts) {
            this.payload = payload;
            this.attempts = attempts;
        }
    }
}