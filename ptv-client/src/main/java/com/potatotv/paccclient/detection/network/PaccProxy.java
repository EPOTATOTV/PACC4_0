package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.detection.network.protocol.GameProtocol;
import com.potatotv.paccclient.detection.network.protocol.PacketDirection;
import com.potatotv.paccclient.detection.network.protocol.PacketParser;
import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络代理层门面（网络代理层 §2）：按环境变量装配 SOCKS5 代理与基岩中继，并把字节流喂给
 * 解析器 / 行为提取器 / 字节检查器。
 *
 * <p>配置来源：{@code PACC_NET_PROXY_PORT}（默认 17170）、{@code PACC_BEDROCK_RELAY_PORT}
 * （默认 19132）、{@code PACC_BEDROCK_RELAY_TARGET}（{@code host:port}，缺省为空则不启动中继）。</p>
 *
 * <p>TCP 侧按 {@code [长度 VarInt]} 切帧后喂 {@link GameProtocol#JAVA} 解析器；UDP 侧数据报直接喂
 * {@link GameProtocol#BEDROCK} 解析器，并旁路读 RakNet 序号供丢包率统计。切帧与解析全程 try/catch，
 * 异常只吞掉、绝不影响转发。全部入口线程安全。</p>
 */
public final class PaccProxy {

    private static final int DEFAULT_PROXY_PORT = 17170;
    private static final int DEFAULT_RELAY_PORT = 19132;
    /** 单帧长度上限，防伪造的长度前缀把缓冲撑爆。 */
    private static final int MAX_FRAME_BYTES = 2_000_000;

    private final int proxyPort;
    private final int relayPort;
    private final String relayHost;
    private final int relayTargetPort;

    private final PacketParser javaParser = new PacketParser(GameProtocol.JAVA);
    private final PacketParser bedrockParser = new PacketParser(GameProtocol.BEDROCK);
    private final BehaviorStreamExtractor extractor = new BehaviorStreamExtractor();
    private final ByteLevelInspector inspector = new ByteLevelInspector();
    private final AtomicInteger byteAnomalies = new AtomicInteger();

    private final DirectionBuffer c2sBuffer = new DirectionBuffer();
    private final DirectionBuffer s2cBuffer = new DirectionBuffer();

    private Socks5Proxy proxy;
    private BedrockRelay relay;
    private boolean started;

    public PaccProxy() {
        this(envInt("PACC_NET_PROXY_PORT", DEFAULT_PROXY_PORT),
                envInt("PACC_BEDROCK_RELAY_PORT", DEFAULT_RELAY_PORT),
                env("PACC_BEDROCK_RELAY_TARGET"));
    }

    /** 显式配置构造（测试/嵌入式用）。{@code relayTarget} 为 {@code host:port} 或空。 */
    public PaccProxy(int proxyPort, int relayPort, String relayTarget) {
        this.proxyPort = proxyPort;
        this.relayPort = relayPort;
        String host = null;
        int port = 0;
        if (relayTarget != null && !relayTarget.isBlank()) {
            int idx = relayTarget.lastIndexOf(':');
            if (idx > 0 && idx < relayTarget.length() - 1) {
                String h = relayTarget.substring(0, idx).trim();
                String p = relayTarget.substring(idx + 1).trim();
                try {
                    port = Integer.parseInt(p);
                    host = h;
                } catch (NumberFormatException e) {
                    host = null;
                    port = 0;
                }
            }
        }
        this.relayHost = host;
        this.relayTargetPort = port;
    }

    /**
     * 按配置启动 SOCKS5 代理与（可选）基岩中继；已启动则直接返回。
     *
     * @return 是否成功启动（SOCKS5 绑定成功即为 true；绑定失败下次调用可重试）
     */
    public synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }
        Socks5Proxy p = new Socks5Proxy(proxyPort, this::onTcpBytes);
        boolean ok = p.start();
        proxy = p;
        if (relayHost != null) {
            BedrockRelay r = new BedrockRelay(relayPort, relayHost, relayTargetPort, this::onUdpBytes);
            r.start();
            relay = r;
        }
        started = ok;
        return ok;
    }

    /** 停止代理与中继并复位切帧缓冲（onRecover / AutoCloseable 复用）。 */
    public synchronized void stop() {
        Socks5Proxy p = proxy;
        if (p != null) {
            p.stop();
        }
        BedrockRelay r = relay;
        if (r != null) {
            r.stop();
        }
        proxy = null;
        relay = null;
        started = false;
        c2sBuffer.reset();
        s2cBuffer.reset();
    }

    public PacketParser javaParser() {
        return javaParser;
    }

    public PacketParser bedrockParser() {
        return bedrockParser;
    }

    public BehaviorStreamExtractor extractor() {
        return extractor;
    }

    public ByteLevelInspector inspector() {
        return inspector;
    }

    /** 累计字节级异常数。 */
    public int byteAnomalyCount() {
        return byteAnomalies.get();
    }

    /** 简短状态串（供事件 detail 使用）：各端口与运行状态、累计转发字节。 */
    public synchronized String status() {
        StringBuilder sb = new StringBuilder();
        Socks5Proxy p = proxy;
        sb.append("socks5=").append(proxyPort)
                .append(p != null && p.running() ? "(running)" : "(stopped)");
        sb.append(" bedrock_relay=");
        if (relayHost == null) {
            sb.append("off");
        } else {
            BedrockRelay r = relay;
            sb.append(relayPort).append(r != null && r.running() ? "(running)" : "(stopped)");
        }
        sb.append(" bytes=").append(bytesRelayed());
        return sb.toString();
    }

    private long bytesRelayed() {
        long total = 0;
        Socks5Proxy p = proxy;
        if (p != null) {
            total += p.bytesRelayed();
        }
        BedrockRelay r = relay;
        if (r != null) {
            total += r.bytesRelayed();
        }
        return total;
    }

    private void onTcpBytes(PacketDirection dir, byte[] data) {
        DirectionBuffer buffer = dir == PacketDirection.C2S ? c2sBuffer : s2cBuffer;
        buffer.append(data, dir);
    }

    private void onUdpBytes(PacketDirection dir, byte[] data) {
        try {
            if (data.length >= 4) {
                long seq = (data[1] & 0xFFL) | ((data[2] & 0xFFL) << 8) | ((data[3] & 0xFFL) << 16);
                extractor.noteSequence(seq);
            }
            ParsedPacket parsed = bedrockParser.parse(data, dir);
            extractor.feed(parsed);
            List<ByteLevelInspector.ByteAnomaly> anomalies = inspector.inspect(data, parsed);
            if (!anomalies.isEmpty()) {
                byteAnomalies.addAndGet(anomalies.size());
            }
        } catch (RuntimeException e) {
            // 解析/检查异常只吞掉，绝不影响转发
        }
    }

    private void dispatchJava(byte[] frame, PacketDirection dir) {
        try {
            ParsedPacket parsed = javaParser.parse(frame, dir);
            extractor.feed(parsed);
            List<ByteLevelInspector.ByteAnomaly> anomalies = inspector.inspect(frame, parsed);
            if (!anomalies.isEmpty()) {
                byteAnomalies.addAndGet(anomalies.size());
            }
        } catch (RuntimeException e) {
            // 解析/检查异常只吞掉，绝不影响转发
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? null : v.trim();
    }

    private static int envInt(String name, int def) {
        String v = env(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 单方向 TCP 重组缓冲：按 {@code [长度 VarInt]} 切帧后分派。 */
    private final class DirectionBuffer {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        synchronized void append(byte[] data, PacketDirection dir) {
            if (data == null || data.length == 0) {
                return;
            }
            buffer.write(data, 0, data.length);
            byte[] arr = buffer.toByteArray();
            int pos = 0;
            while (true) {
                int end = nextFrameEnd(arr, pos);
                if (end == -1) {
                    break;                      // 数据不足，等更多字节
                }
                if (end == -2) {
                    pos = arr.length;           // 非法长度，丢弃整段
                    break;
                }
                byte[] frame = Arrays.copyOfRange(arr, pos, end);
                pos = end;
                dispatchJava(frame, dir);
            }
            if (pos > 0) {
                byte[] rest = Arrays.copyOfRange(arr, pos, arr.length);
                buffer.reset();
                if (rest.length > 0) {
                    buffer.write(rest, 0, rest.length);
                }
            }
        }

        synchronized void reset() {
            buffer.reset();
        }

        /**
         * 若 {@code pos} 处存在完整帧返回其结束下标（不含）；{@code -1} 表示数据不足需等待；
         * {@code -2} 表示长度非法需丢弃。
         */
        private int nextFrameEnd(byte[] arr, int pos) {
            int p = pos;
            int value = 0;
            int shift = 0;
            boolean complete = false;
            while (p < arr.length) {
                byte b = arr[p++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    complete = true;
                    break;
                }
                shift += 7;
                if (shift >= 32) {
                    return -2;
                }
            }
            if (!complete) {
                return -1;
            }
            if (value < 0 || value > MAX_FRAME_BYTES) {
                return -2;
            }
            int total = (p - pos) + value;
            if (arr.length - pos < total) {
                return -1;
            }
            return pos + total;
        }
    }
}