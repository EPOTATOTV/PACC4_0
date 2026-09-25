package com.potatotv.paccclient.apm;

import com.potatotv.paccclient.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * APM 采集器：秒级采样 → 本地环形缓冲 → 5 分钟批量上报。
 *
 * <p>设计要点（文档「APM 采集子系统」）：</p>
 * <ul>
 *   <li>全部任务跑在单个守护虚拟线程调度器上，采样周期 1s；进程 CPU 升高时自适应降级到 5s/10s，
 *       保证整个 APM 子系统开销 &lt; 0.5% CPU。</li>
 *   <li>采集器不认识 HTTP：上报走构造注入的 {@link BatchSink}，返回是否成功，
 *       失败时快照留在缓冲里等下一轮，成功后才丢弃。</li>
 *   <li>缓冲上限 10080 条（7 天 × 1440 分钟），长期离线也不会无限增长。</li>
 * </ul>
 *
 * <p>指标 JSON 用手写 StringBuilder 拼装：500 条样本若先构造 Map 树再序列化，单次 flush 会分配
 * 上万个中间对象，得不偿失。</p>
 */
public final class ApmCollector {

    /** 与版本元数据一致的客户端版本号。 */
    public static final String CLIENT_VERSION = "5.4.0";

    /**
     * 批量上报出口。返回是否成功：只有知道结果才能决定「丢弃还是保留」。
     * <p>注意：本接口按语义需要返回 boolean，而非 void——否则无法实现「失败保留」的离线兜底。</p>
     */
    @FunctionalInterface
    public interface BatchSink {
        boolean send(String jsonPayload);
    }

    /** 7 天容量（分钟数）。 */
    private static final int BUFFER_CAPACITY = 10080;
    /** 单次 flush 最多发送的样本数。 */
    private static final int FLUSH_BATCH = 500;
    private static final long FLUSH_PERIOD_SECONDS = 300;
    /** 载荷中应上报为单调计数器的指标（与上报契约 type 字段对应）。 */
    private static final Set<String> COUNTER_METRICS = Set.of(
            "client_uptime", "client_crash_count", "client_redscreen_count",
            "client_wss_reconnect_count", "detect_model_infer_count",
            "sys_uptime", "game_frame_stutter");

    private final BatchSink sink;
    private final SystemMetrics systemMetrics = new SystemMetrics();
    // 帧/输入样本只能由检测循环喂入：本进程与游戏并列运行（不在游戏内），没有游戏主循环可挂。
    // 因此构造点只创建采集器，喂样与「不喂就不产出」的取舍见 GameMetrics 的说明。
    private final GameMetrics gameMetrics = new GameMetrics();
    private final DetectionMetrics detectionMetrics = new DetectionMetrics();
    private final ClientHealthMetrics healthMetrics = ClientHealthMetrics.SINK;
    private final ApmRingBuffer buffer = new ApmRingBuffer(BUFFER_CAPACITY);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> samplingTask;
    private volatile int sampleIntervalSeconds = 1;

    public ApmCollector(BatchSink sink) {
        this.sink = sink;
    }

    /** 健康维度采集器（与 {@link ClientHealthMetrics#SINK} 同一实例）。 */
    public ClientHealthMetrics health() {
        return healthMetrics;
    }

    /** 游戏帧时序采集器；帧/输入喂入由检测循环负责。 */
    public GameMetrics game() {
        return gameMetrics;
    }

    /** 启动调度：采样任务 + 5 分钟 flush 任务 + 10s 自适应任务。重复调用无副作用。 */
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-apm").unstarted(r));
        scheduleSampling();
        // 首轮 flush 提前到 60s：启动后不久就有一批数据可发，便于确认链路是否通
        executor.scheduleWithFixedDelay(this::flushQuietly, 60, FLUSH_PERIOD_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::adaptInterval, 10, 10, TimeUnit.SECONDS);
    }

    /** 停止调度并丢弃未发送任务。 */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            samplingTask = null;
        }
    }

    /** 当前缓冲条数（诊断用）。 */
    public int bufferedCount() {
        return buffer.size();
    }

    /** 当前采样周期（秒）。 */
    public int sampleIntervalSeconds() {
        return sampleIntervalSeconds;
    }

    /** 平台标识：与上报契约一致（WIN / LINUX / MAC）。 */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "WIN";
        if (os.contains("mac")) return "MAC";
        return "LINUX";
    }

    /**
     * 取最旧的至多 500 条上报：成功则丢弃，失败则保留（不重复追加，避免同一批数据在缓冲里翻倍）。
     * 返回是否全部发送成功。
     */
    public boolean flush() {
        List<ApmSnapshot> batch = buffer.peekOldest(FLUSH_BATCH);
        if (batch.isEmpty()) return true;
        boolean ok;
        try {
            ok = sink.send(encode(batch));
        } catch (Exception e) {
            System.err.println("[PTV-APM] 批量上报异常（保留缓冲，稍后重试）: " + e.getMessage());
            ok = false;
        }
        if (ok) {
            buffer.removeOldest(batch.size());
        }
        healthMetrics.onReport(ok);
        healthMetrics.setReportQueueSize(buffer.size());
        return ok;
    }

    /**
     * 编码为上报契约要求的 JSON：
     * {@code {"client_version":"5.4.0","platform":"WIN","samples":[{"metric_time":..,"name":..,"value":..,"type":"gauge","tags":{}}]}}。
     * 非有限值（NaN/Inf）直接跳过。
     */
    public String encode(List<ApmSnapshot> samples) {
        String platform = samples.isEmpty() ? platform() : samples.get(0).platform();
        StringBuilder sb = new StringBuilder(256 + samples.size() * 8 * 48);
        sb.append("{\"client_version\":").append(Json.encode(CLIENT_VERSION))
                .append(",\"platform\":").append(Json.encode(platform))
                .append(",\"samples\":[");
        boolean first = true;
        for (ApmSnapshot snapshot : samples) {
            for (Map.Entry<String, Double> entry : snapshot.metrics().entrySet()) {
                double value = entry.getValue();
                if (!Double.isFinite(value)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"metric_time\":").append(snapshot.timestamp())
                        .append(",\"name\":").append(Json.encode(entry.getKey()))
                        .append(",\"value\":").append(number(value))
                        .append(",\"type\":\"").append(COUNTER_METRICS.contains(entry.getKey()) ? "counter" : "gauge")
                        .append("\",\"tags\":{}}");
            }
        }
        return sb.append("]}").toString();
    }

    private synchronized void scheduleSampling() {
        if (executor == null) return;
        int interval = sampleIntervalSeconds;
        samplingTask = executor.scheduleWithFixedDelay(this::sampleOnce, interval, interval, TimeUnit.SECONDS);
    }

    private void sampleOnce() {
        try {
            ApmSnapshot snapshot = new ApmSnapshot(System.currentTimeMillis(), platform());
            systemMetrics.collect(snapshot);
            gameMetrics.collect(snapshot);
            detectionMetrics.collect(snapshot);
            healthMetrics.collect(snapshot);
            buffer.add(snapshot);
        } catch (RuntimeException | LinkageError e) {
            // 采样失败只丢这一次切片，绝不影响检测链路与其他任务
            System.err.println("[PTV-APM] 采样异常（跳过本次）: " + e.getMessage());
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            System.err.println("[PTV-APM] flush 异常: " + e.getMessage());
        }
    }

    /** 自适应降采样：CPU &gt;20% → 10s；&gt;10% → 5s；&lt;5% → 1s；介于 5%-10% 维持现状避免抖动。 */
    private synchronized void adaptInterval() {
        int current = sampleIntervalSeconds;
        double cpu = systemMetrics.processCpuPercent();
        int next;
        if (cpu > 20) next = 10;
        else if (cpu > 10) next = 5;
        else if (cpu < 5) next = 1;
        else next = current;
        if (next == current) return;
        System.out.println("[PTV-APM] 采样周期自适应 " + current + "s→" + next + "s（进程 CPU "
                + Math.round(cpu) + "%）");
        sampleIntervalSeconds = next;
        if (samplingTask != null) samplingTask.cancel(false);
        scheduleSampling();
    }

    /** 数值格式化：整数值不带小数点尾巴，其余保留 double 原生表示。 */
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}