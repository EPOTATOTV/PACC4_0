package com.potatotv.paccclient.control;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEngine;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.stream.StreamDetectionPipeline;
import com.potatotv.paccclient.detection.stream.StreamEvent;
import com.potatotv.paccclient.transport.WssReporter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 检测引擎的启停控制器：把 PaccClient 里的周期采样调度抽成可被本地控制服务
 * start/stop 的统一入口，并留存最近检测事件供桌面壳/前端查询。默认从启动起运行，
 * 远程/桌面壳可按需暂停、恢复。默认启动即运行，且与历史行为保持一致。
 */
public final class DetectionController {

    /** 可在运行时调整的端侧行为参数（由本地控制服务下发热更新）。 */
    public static final class RuntimeConfig {
        public volatile int clientRisk;
        public volatile double heartbeatSeconds;
        public volatile boolean enabled;

        public RuntimeConfig(int clientRisk, double heartbeatSeconds, boolean enabled) {
            this.clientRisk = clientRisk;
            this.heartbeatSeconds = Math.max(2, heartbeatSeconds);
            this.enabled = enabled;
        }
    }

    private static final int MAX_RECENT = 64;

    private final DetectionEngine engine;
    private final WssReporter reporter;
    private final RuntimeConfig cfg;
    private final long startedAt = System.currentTimeMillis();
    private final Deque<String> recent = new ArrayDeque<>(MAX_RECENT + 1);

    private ScheduledExecutorService scheduler;

    /** 可选的实时流式检测管线（见 {@link #attachStreamPipeline}）：未接入时为 null，行为与历史一致。 */
    private volatile StreamDetectionPipeline stream;
    private volatile String streamPteid = "";

    public DetectionController(DetectionEngine engine, WssReporter reporter, RuntimeConfig cfg) {
        this.engine = engine;
        this.reporter = reporter;
        this.cfg = cfg;
    }

    /**
     * 接入实时流式检测管线（DF §4.1.1）：此后每个检测事件都会同时投递一份 {@link StreamEvent}。
     * <p>可选接线——不接入时本类行为与加固前完全一致；投递是无锁入队，不阻塞采样线程，
     * 管线内部异常也不会传播回来。</p>
     *
     * @param pipeline 流式管线（由调用方负责 {@code start()} 与 {@code close()}）
     * @param pteid    玩家标识（写入流式事件与判定）
     */
    public void attachStreamPipeline(StreamDetectionPipeline pipeline, String pteid) {
        this.streamPteid = pteid == null ? "" : pteid;
        this.stream = pipeline;
    }

    public RuntimeConfig cfg() {
        return cfg;
    }

    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    public synchronized void start() {
        if (scheduler != null || !cfg.enabled) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-detector").unstarted(r));
        s.scheduleWithFixedDelay(this::sampleAndReport, 2, (long) cfg.heartbeatSeconds, TimeUnit.SECONDS);
        scheduler = s;
    }

    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
    }

    public long uptimeSec() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }

    public synchronized List<String> recentDetections(int limit) {
        int n = Math.min(limit <= 0 ? MAX_RECENT : limit, recent.size());
        List<String> out = new ArrayList<>(n);
        Iterator<String> it = recent.iterator();
        for (int i = 0; i < n && it.hasNext(); i++) out.add(it.next());
        return out;
    }

    /** 最近一次检测事件类型（无则空串），供调度轮询事件推送使用。 */
    public synchronized String lastEventType() {
        if (recent.isEmpty()) return "";
        String last = recent.peekLast();
        try {
            Map<String, Object> m = Json.decodeObject(last);
            Object t = m.get("event_type");
            return t == null ? "" : String.valueOf(t);
        } catch (Exception e) {
            return "";
        }
    }

    private void sampleAndReport() {
        try {
            engine.sample(cfg.clientRisk).ifPresent(e -> {
                record(e);
                reporter.report(e);
                StreamDetectionPipeline pipeline = stream;
                if (pipeline != null) {
                    pipeline.submit(toStreamEvent(e));
                }
            });
        } catch (RuntimeException ex) {
            System.err.println("[PTV-Client] 采样失败: " + ex.getMessage());
        }
    }

    /**
     * 检测事件 → 流式事件。
     *
     * <p>{@code signal} 恒为 1.0：事件本身就代表「信号已确认存在」，而存在性规则（内存篡改、
     * 进程注入、Java 模组等）的信号上限恰好是 1.0。原始量纲（连点 CPS、自瞄角速度）不在
     * {@link DetectionEvent} 里，不在这里伪造成业务量纲；连续量纲型规则交由流式层的节律窗口与
     * AI 精判补足。</p>
     */
    private StreamEvent toStreamEvent(DetectionEvent e) {
        return new StreamEvent(streamPteid, e.eventType(), e.severity(), 1.0, featuresOf(e), 0L, 0L);
    }

    /** 从 {@code detailJson} 还原数值特征；缺失或非法时按「无特征」投递，绝不阻断采样。 */
    private static Map<String, Double> featuresOf(DetectionEvent e) {
        String detail = e.detailJson();
        if (detail == null || detail.isBlank() || !detail.trim().startsWith("{")) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = Json.decodeObject(detail);
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : raw.entrySet()) {
                if (en.getValue() instanceof Number n) {
                    out.put(en.getKey(), n.doubleValue());
                }
            }
            return out;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private synchronized void record(DetectionEvent e) {
        String j = Json.encode(toMap(e));
        recent.addLast(j);
        while (recent.size() > MAX_RECENT) recent.removeFirst();
    }

    private static Map<String, Object> toMap(DetectionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_type", e.eventType());
        m.put("severity", e.severity());
        m.put("client_risk", e.clientRiskScore());
        m.put("process", e.processName());
        m.put("signature", e.signatureHit());
        m.put("detail", e.detailJson());
        return m;
    }
}