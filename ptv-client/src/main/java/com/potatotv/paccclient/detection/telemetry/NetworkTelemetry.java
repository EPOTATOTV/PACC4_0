package com.potatotv.paccclient.detection.telemetry;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网络特征遥测（文档 §2.2.1 网络特征 17 维，数据源为本地网络栈只读采样）。
 *
 * <p>接口拓扑、MTU、链路速率均来自 {@link NetworkInterface}；
 * Linux 上额外读取 {@code /sys/class/net/<if>/statistics/} 得到真实收发字节与上行/下行占比。
 * RTT / 抖动 / 丢包通过对本地回环的一次短探针测得（真实 TCP 往返，非估算）。</p>
 *
 * <p>纯 JDK 无法被动获取的维度（{@code feature_packet_anomaly_ratio}、{@code feature_net_packet_rate}、
 * {@code feature_net_retransmit_ratio}）恒置 0 且不计入覆盖度，由 Java Agent / 平台层回填。
 * 绝不抛出。</p>
 */
public final class NetworkTelemetry {

    /** 回环探针往返次数（低开销，够估计均值与抖动）。 */
    private static final int PROBE_ROUNDS = 8;
    /** 单次连接/读取超时（ms）。 */
    private static final int PROBE_TIMEOUT_MS = 200;
    private static final String[] VIRTUAL_KEYS = {
            "vmnet", "vbox", "virtual", "vethernet", "hyper-v", "docker", "br-", "tun", "tap",
            "wsl", "loopback", "virbr", "utun", "bridge"};

    private NetworkTelemetry() {
    }

    /** 采集一次网络快照。 */
    public static TelemetrySnapshot snapshot() {
        Map<String, Double> v = new LinkedHashMap<>();
        Set<String> backed = new HashSet<>();
        readInterfaces(v, backed);
        readInterfaceCounters(v, backed);
        readLinkSpeed(v, backed);
        readProxy(v, backed);
        readLoopbackProbe(v, backed);
        return new TelemetrySnapshot(v, backed);
    }

    private static void readInterfaces(Map<String, Double> v, Set<String> backed) {
        try {
            List<NetworkInterface> ifaces = new ArrayList<>();
            var it = NetworkInterface.getNetworkInterfaces();
            if (it != null) {
                while (it.hasMoreElements()) ifaces.add(it.nextElement());
            }
            if (ifaces.isEmpty()) return;

            int total = 0;
            int loopback = 0;
            int virtual = 0;
            double mtuSum = 0;
            for (NetworkInterface ni : ifaces) {
                total++;
                if (ni.isLoopback()) loopback++;
                if (ni.isVirtual() || isVirtualName(ni.getName())) virtual++;
                int mtu = safeMtu(ni);
                if (mtu > 0) mtuSum += mtu;
            }
            v.put("feature_net_interface_count", (double) total);
            v.put("feature_net_loopback_ratio", (double) loopback / total);
            v.put("feature_net_virtual_ratio", (double) virtual / total);
            v.put("feature_net_mtu_mean", mtuSum / total);
            backed.add("feature_net_interface_count");
            backed.add("feature_net_loopback_ratio");
            backed.add("feature_net_virtual_ratio");
            backed.add("feature_net_mtu_mean");
        } catch (Exception e) {
            // 忽略
        }
    }

    /** Linux：从 /sys/class/net/<if>/statistics 读取真实收发字节。 */
    private static void readInterfaceCounters(Map<String, Double> v, Set<String> backed) {
        try {
            Path net = Path.of("/sys/class/net");
            if (!Files.isDirectory(net)) return;
            long rx = 0;
            long tx = 0;
            boolean any = false;
            try (var dirs = Files.list(net)) {
                for (Path iface : dirs.toList()) {
                    long r = readLong(iface.resolve("statistics/rx_bytes"));
                    long t = readLong(iface.resolve("statistics/tx_bytes"));
                    if (r >= 0) rx += r;
                    if (t >= 0) tx += t;
                    if (r >= 0 || t >= 0) any = true;
                }
            }
            if (!any) return;
            v.put("feature_net_bytes_recv", (double) rx);
            v.put("feature_net_bytes_sent", (double) tx);
            backed.add("feature_net_bytes_recv");
            backed.add("feature_net_bytes_sent");
            long sum = rx + tx;
            if (sum > 0) {
                v.put("feature_net_upload_ratio", (double) tx / sum);
                v.put("feature_net_down_ratio", (double) rx / sum);
                backed.add("feature_net_upload_ratio");
                backed.add("feature_net_down_ratio");
            }
        } catch (Exception e) {
            // Windows / 权限受限：跳过
        }
    }

    private static long readLong(Path p) {
        try {
            if (!Files.isReadable(p)) return -1;
            return Long.parseLong(Files.readString(p).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Linux：从 {@code /sys/class/net/<if>/speed} 读取链路速率（Mbps），Windows 上置 0 且不计入覆盖度。 */
    private static void readLinkSpeed(Map<String, Double> v, Set<String> backed) {
        try {
            Path net = Path.of("/sys/class/net");
            if (!Files.isDirectory(net)) return;
            double sum = 0;
            int count = 0;
            try (var dirs = Files.list(net)) {
                for (Path iface : dirs.toList()) {
                    long mbps = readLong(iface.resolve("speed"));
                    if (mbps > 0) {
                        sum += mbps;
                        count++;
                    }
                }
            }
            if (count == 0) return;
            v.put("feature_net_speed_mean", sum / count);
            backed.add("feature_net_speed_mean");
        } catch (Exception e) {
            // Windows / 权限受限：跳过
        }
    }

    /** 系统代理配置探测（Java 系统属性 / 环境变量）。 */
    private static void readProxy(Map<String, Double> v, Set<String> backed) {
        try {
            boolean proxy = notBlank(System.getProperty("http.proxyHost"))
                    || notBlank(System.getProperty("https.proxyHost"))
                    || notBlank(System.getProperty("socksProxyHost"))
                    || notBlank(System.getenv("HTTP_PROXY"))
                    || notBlank(System.getenv("HTTPS_PROXY"))
                    || notBlank(System.getenv("ALL_PROXY"));
            v.put("feature_proxy_detected", proxy ? 1.0 : 0.0);
            backed.add("feature_proxy_detected");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    /**
     * 回环 TCP 往返探针：真实测量 RTT 均值/标准差（抖动）与丢包，并据此给出异常延迟标志。
     * 探针总预算 <2s，失败时该组维度置 0 且不计入覆盖度。
     */
    private static void readLoopbackProbe(Map<String, Double> v, Set<String> backed) {
        ServerSocket server = null;
        Thread accepter = null;
        try {
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            ServerSocket bound = server;
            accepter = new Thread(() -> {
                while (!bound.isClosed()) {
                    try (Socket s = bound.accept()) {
                        s.setSoTimeout(PROBE_TIMEOUT_MS);
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();
                        out.write(1);
                        out.flush();
                        in.read();
                    } catch (Exception ignored) {
                        // 服务端随套接字关闭退出
                    }
                }
            }, "pacc-net-probe");
            accepter.setDaemon(true);
            accepter.start();

            InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            double[] rtt = new double[PROBE_ROUNDS];
            int lost = 0;
            int ok = 0;
            for (int i = 0; i < PROBE_ROUNDS; i++) {
                long start = System.nanoTime();
                try (Socket c = new Socket()) {
                    c.connect(addr, PROBE_TIMEOUT_MS);
                    c.setSoTimeout(PROBE_TIMEOUT_MS);
                    c.getOutputStream().write(1);
                    c.getOutputStream().flush();
                    c.getInputStream().read();
                    rtt[ok++] = (System.nanoTime() - start) / 1_000_000.0;
                } catch (Exception e) {
                    lost++;
                }
            }
            if (ok == 0) return;
            double mean = 0;
            for (int i = 0; i < ok; i++) mean += rtt[i];
            mean /= ok;
            double var = 0;
            for (int i = 0; i < ok; i++) var += (rtt[i] - mean) * (rtt[i] - mean);
            var /= ok;
            double jitter = Math.sqrt(var);
            v.put("feature_net_rtt_ms", mean);
            v.put("feature_net_jitter_ms", jitter);
            v.put("feature_net_packet_loss_ratio", (double) lost / PROBE_ROUNDS);
            // 异常延迟：抖动显著高于基线（回环 jitter 基线取 1ms）视为异常
            v.put("feature_abnormal_latency", jitter > 1.0 ? 1.0 : 0.0);
            backed.add("feature_net_rtt_ms");
            backed.add("feature_net_jitter_ms");
            backed.add("feature_net_packet_loss_ratio");
            backed.add("feature_abnormal_latency");
        } catch (Exception e) {
            // 探针不可用：跳过
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            if (accepter != null) accepter.interrupt();
        }
    }

    private static int safeMtu(NetworkInterface ni) {
        try {
            int mtu = ni.getMTU();
            return mtu > 0 ? mtu : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isVirtualName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String k : VIRTUAL_KEYS) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}