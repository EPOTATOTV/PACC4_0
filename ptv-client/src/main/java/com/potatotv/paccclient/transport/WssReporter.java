package com.potatotv.paccclient.transport;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.store.OfflineQueue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.List;

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
    private final String wssSignSecret;
    private final Consumer<String> onMessage;
    private final OfflineQueue offlineQueue;

    /** 二进制帧回调（会话密钥握手）。默认空实现，不影响既有链路。 */
    private volatile Consumer<byte[]> onBinaryMessage;
    /** 每次连接（含重连）成功后触发，用于发起 session_init 重新协商密钥。 */
    private volatile Runnable onConnected;

    /** 注册二进制帧处理器（服务端 session_ready / session_ack）。 */
    public void setOnBinaryMessage(Consumer<byte[]> handler) {
        this.onBinaryMessage = handler;
    }

    /** 注册「连接已建立」回调；重连成功同样触发，因此会话密钥不会跨连接复用。 */
    public void setOnConnected(Runnable handler) {
        this.onConnected = handler;
    }

    private static final SecureRandom RAND = new SecureRandom();
    private static final long SIGN_WINDOW_SECONDS = 300;

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
                       String wssSignSecret, Consumer<String> onMessage, OfflineQueue offlineQueue) {
        this.pteid = pteid;
        this.edition = edition;
        this.wssUri = wssUri;
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
        this.signatureVersion = signatureVersion;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.autoReconnect = autoReconnect;
        this.wssSignSecret = wssSignSecret == null ? "" : wssSignSecret;
        this.onMessage = onMessage;
        this.offlineQueue = offlineQueue;
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
        flushOfflineQueue();
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
        // 在锁外触发：回调会经 socket 发帧，持锁执行容易和发送路径互相等待。
        // 每次连接（含重连）都触发一次，调用方据此重新协商会话密钥。
        Runnable cb = onConnected;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                System.err.println("[PTV-Client] 连接后回调失败: " + rootMessage(e));
            }
        }
    }

    /** 上报一条检测事件（虚拟线程异步发送）。 */
    public void report(DetectionEvent event) {
        eventSender.submit(() -> send(detectionEventPayload(event)));
    }

    /**
     * 直接发送一条已带 {@code type} 的信令消息（远程查端 / 本地取证回传）。
     * 与原 {@code send} 一致会附加 HMAC 签名；断线时按非心跳规则入离线队列。
     */
    public void sendPayload(Map<String, Object> payload) {
        send(payload);
    }

    /**
     * 以二进制帧发送一条已签名的 protobuf 信封（查端信令经 protobuf 收发）。
     * 断线时不入离线队列（离线队列暂存 JSON 文本事件；查端信令为实时信令）。
     */
    public void sendEnvelope(byte[] envelopeBytes) {
        WebSocket s = socket;
        if (s == null || envelopeBytes == null) return;
        try {
            s.sendBinary(java.nio.ByteBuffer.wrap(envelopeBytes), true);
        } catch (Exception e) {
            System.err.println("[PTV-Client] 发送信封失败: " + e.getMessage());
        }
    }

    private void send(Map<String, Object> body) {
        String payload = Json.encode(sign(body));
        WebSocket s = socket;
        if (s == null) {
            // 通道断开：非心跳消息入离线队列，等待重连补报
            if (!"ping".equals(body.get("type"))) enqueueOffline(payload);
            return;
        }
        try {
            s.sendText(payload, true);
        } catch (Exception e) {
            System.err.println("[PTV-Client] 发送失败: " + e.getMessage());
            enqueueOffline(payload);
        }
    }

    /** 发送失败/断开时写入离线队列（若配置了离线存储）。 */
    private void enqueueOffline(String payload) {
        if (offlineQueue != null) {
            offlineQueue.offer(payload);
        }
    }

    /** 通道恢复后取出离线队列并批量补报；逐条失败则重新入队。 */
    private void flushOfflineQueue() {
        if (offlineQueue == null) return;
        List<String> drained = offlineQueue.drain();
        if (drained.isEmpty()) return;
        int sent = 0;
        for (String payload : drained) {
            WebSocket s = socket;
            if (s == null) {
                offlineQueue.offer(payload);
                break;
            }
            try {
                s.sendText(payload, true);
                sent++;
            } catch (Exception e) {
                offlineQueue.offer(payload);
                break;
            }
        }
        System.out.println("[PTV-Client] 离线补报完成: 成功 " + sent + "/" + drained.size());
    }

    /**
     * 为消息附加防重放签名（仅当配置了签名密钥时）。
     * <p>签名覆盖 {@code pteid + "." + ts + "." + nonce + "." + 规范化载荷}，
     * 服务器用相同密钥复算校验，可识别篡改与重放。</p>
     */
    private Map<String, Object> sign(Map<String, Object> body) {
        if (wssSignSecret.isBlank()) return body;
        LinkedHashMap<String, Object> m = new LinkedHashMap<>(body);
        long ts = System.currentTimeMillis() / 1000;
        byte[] nonceBytes = new byte[8];
        RAND.nextBytes(nonceBytes);
        String nonce = hex(nonceBytes);
        m.put("ts", ts);
        m.put("nonce", nonce);
        // 规范载荷：去掉 sig 后按当前键序编码（服务器反序列化后保持同序复算）
        String canonical = Json.encode(m);
        String sig = hmacHex(wssSignSecret, pteid + "." + ts + "." + nonce + "." + canonical);
        m.put("sig", sig);
        return m;
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
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
            flushOfflineQueue();
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
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            // 服务端的 session_ready / session_ack 走二进制帧；转发给会话密钥状态机。
            // 不在此处阻塞：只是把字节交出去，避免在 WebSocket 回调里等待握手结果导致死锁。
            if (last && onBinaryMessage != null) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    onBinaryMessage.accept(bytes);
                } catch (Exception e) {
                    System.err.println("[PTV-Client] 处理二进制帧失败: " + e.getMessage());
                }
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
