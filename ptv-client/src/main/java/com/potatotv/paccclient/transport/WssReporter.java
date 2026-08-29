package com.potatotv.paccclient.transport;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * PTV 长连接上报器：与 PTV 管控服务器建立 WSS（TLS 1.3）通道，
 * 上报检测事件，并接收服务端下发的红屏 / 缓解指令。
 * <p>通道独立于游戏连接，仅用于反作弊数据，绝不经由游戏服务器。</p>
 * 支持断线自动重连（指数退避）：握手失败在启动阶段持续重试；
 * 连接建立后断开也会自动重连，保证玩家端长连接的高可用。</p>
 * <p>事件载荷由 {@link com.potatotv.paccclient.Json} 编码，字节流一次性发送。</p>
 */
public final class WssReporter implements AutoCloseable {

    private final String pteid;
    private final String edition;
    private final String wssUri;
    private final int heartbeatSeconds;
    private final String signatureVersion;
    private final int reconnectDelaySeconds;
    private final boolean autoReconnect;
    private final Consumer<String> onMessage;

    private final HttpClient client = HttpClient.newBuilder().build();
    private final Object lock = new Object();

    private volatile WebSocket socket;
    private volatile boolean running = true;
    private volatile boolean everConnected;

    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().name("ptv-heartbeat").unstarted(r));
    private final ScheduledExecutorService reconnector =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().name("ptv-reconnect").unstarted(r));
    private final ExecutorService eventSender = Executors.newVirtualThreadPerTaskExecutor();

    public WssReporter(String pteid, String edition, String wssUri, int heartbeatSeconds,
                       String signatureVersion, int reconnectDelaySeconds, boolean autoReconnect,
                       Consumer<String> onMessage) {
        this.pteid = pteid;
        this.edition = edition;
        this.wssUri = wssUri;
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
        this.signatureVersion = signatureVersion;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.autoReconnect = autoReconnect;
        this.onMessage = onMessage;
    }

    /**
     * 建立连接并注册心跳。
     * <p>启动阶段若服务器暂不可达，将按重连间隔持续重试直至成功或进程退出。</p>
     */
    public void connect() throws InterruptedException {
        while (running) {
            try {
                connectOnce();
                break;
            } catch (Exception e) {
                System.err.println("[PTV-Client] 连接失败，稍后重试: " + rootMessage(e));
                Thread.sleep(reconnectDelaySeconds * 1000L);
            }
        }
        if (!running) return;
        heartbeat.scheduleWithFixedDelay(() -> send(Map.of("type", "ping")),
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        System.out.println("[PTV-Client] WSS 已连接: " + wssUri + " (pteid=" + pteid + ", edition=" + edition + ")");
    }

    /** 执行一次握手。成功则替换 socket；失败抛出异常由上层重试。 */
    private void connectOnce() throws InterruptedException {
        synchronized (lock) {
            Listener listener = new Listener();
            WebSocket ws;
            try {
                ws = client.newWebSocketBuilder()
                        .buildAsync(URI.create(wssUri), listener)
                        .join();
            } catch (Exception e) {
                socket = null;
                throw new IllegalStateException("WSS 握手失败: " + rootMessage(e), e);
            }
            socket = ws;
            everConnected = true;
        }
    }

    /** 上报一条检测事件（虚拟线程异步发送）。 */
    public void report(DetectionEvent event) {
        eventSender.submit(() -> send(detectionEventPayload(event)));
    }

    private void send(Map<String, Object> body) {
        WebSocket s = socket;
        if (s == null) return; // 通道断开期间静默丢弃，等待自动重连
        try {
            s.sendText(Json.encode(body), true);
        } catch (Exception e) {
            System.err.println("[PTV-Client] 发送失败: " + e.getMessage());
        }
    }

    private Map<String, Object> detectionEventPayload(DetectionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "event");
        m.put("event_type", e.eventType());
        m.put("severity", e.severity());
        m.put("client_risk_score", e.clientRiskScore());
        m.put("process_name", e.processName());
        m.put("memory_region", e.memoryRegion());
        m.put("signature_hit", e.signatureHit());
        m.put("os_info", e.osInfo());
        m.put("client_version", signatureVersion);
        m.put("detail", buildDetail(e));
        // v4.1：128 维行为特征向量（供 PTV AI 行为画像引擎）
        if (e.detailJson() != null && !e.detailJson().isBlank()) {
            m.put("features", e.detailJson());
        }
        return m;
    }

    /** 由事件各维度拼装服务端可读的取证描述（替代原来的 severity 占位）。 */
    private static String buildDetail(DetectionEvent e) {
        StringBuilder sb = new StringBuilder("端侧检测: ").append(e.eventType());
        if (e.processName() != null) sb.append(" | 进程=").append(e.processName());
        if (e.memoryRegion() != null) sb.append(" | 内存区=").append(e.memoryRegion());
        if (e.signatureHit() != null) sb.append(" | 特征码=").append(e.signatureHit());
        return sb.toString();
    }

    /** 断线后调度自动重连。 */
    private void scheduleReconnect(String reason) {
        if (!running) return;
        System.err.println("[PTV-Client] 连接断开(" + reason + ")，" + reconnectDelaySeconds + "s 后自动重连...");
        reconnector.schedule(this::reconnectNow, reconnectDelaySeconds, TimeUnit.SECONDS);
    }

    private void reconnectNow() {
        if (!running) return;
        try {
            connectOnce();
            System.out.println("[PTV-Client] 已自动重连: " + wssUri);
        } catch (Exception e) {
            scheduleReconnect("重连失败: " + rootMessage(e));
        }
    }

    @Override
    public void close() {
        running = false;
        heartbeat.shutdownNow();
        reconnector.shutdownNow();
        eventSender.shutdownNow();
        WebSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "app-exit");
            } catch (Exception ignored) {
                s.abort();
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                System.out.println("[PTV-Client] 收到服务端消息: " + msg);
                onMessage.accept(msg);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[PTV-Client] 连接关闭: " + statusCode + " " + reason);
            synchronized (lock) {
                socket = null;
            }
            if (autoReconnect && everConnected && running) {
                scheduleReconnect("close:" + statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[PTV-Client] 连接错误: " + error.getMessage());
            synchronized (lock) {
                socket = null;
            }
            if (autoReconnect && everConnected && running) {
                scheduleReconnect("error:" + error.getMessage());
            }
        }
    }
}
