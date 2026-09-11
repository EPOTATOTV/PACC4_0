package com.potatotv.paccclient.control;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEngine;
import com.potatotv.paccclient.detection.DetectionEvent;
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

    public DetectionController(DetectionEngine engine, WssReporter reporter, RuntimeConfig cfg) {
        this.engine = engine;
        this.reporter = reporter;
        this.cfg = cfg;
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
            });
        } catch (RuntimeException ex) {
            System.err.println("[PTV-Client] 采样失败: " + ex.getMessage());
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