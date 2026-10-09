package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.detection.network.protocol.PacketDirection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基岩版 UDP 中继（网络代理层 §2.5）：玩家把服务器地址填 {@code 127.0.0.1:19132}，
 * 中继把数据报转发到真实服务器，并把回包转回<b>最近活跃客户端</b>（记住 {@code lastClient}）。
 *
 * <p>只转发、不改字节；收到哪个方向的数据报就把原始字节交给 {@link Socks5Proxy.PacketTap}
 * （来源地址等于目标地址即 S2C，否则视为客户端 C2S 并更新 {@code lastClient}）。绑定 127.0.0.1，
 * 失败安静返回 false，线程为守护线程。</p>
 */
public final class BedrockRelay {

    private static final int BUFFER_SIZE = 65507;

    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final Socks5Proxy.PacketTap tap;
    private final AtomicLong bytesRelayed = new AtomicLong();

    private volatile DatagramSocket socket;
    private volatile boolean running;
    private volatile SocketAddress lastClient;

    public BedrockRelay(int listenPort, String targetHost, int targetPort, Socks5Proxy.PacketTap tap) {
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.tap = tap;
    }

    /** 绑定 127.0.0.1 监听端口并开始中继；失败返回 false，不抛异常。 */
    public boolean start() {
        if (running) {
            return true;
        }
        try {
            InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(targetHost), targetPort);
            DatagramSocket s = new DatagramSocket(null);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort));
            socket = s;
            running = true;
            Thread t = new Thread(() -> receiveLoop(target), "pacc-bedrock-relay");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException | RuntimeException e) {
            running = false;
            closeQuietly(socket);
            socket = null;
            return false;
        }
    }

    /** 停止中继并关闭 socket。 */
    public void stop() {
        running = false;
        closeQuietly(socket);
        socket = null;
    }

    public boolean running() {
        return running;
    }

    /** 双向累计转发的字节数。 */
    public long bytesRelayed() {
        return bytesRelayed.get();
    }

    private void receiveLoop(InetSocketAddress target) {
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        byte[] buf = new byte[BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                int len = p.getLength();
                if (len <= 0) {
                    continue;
                }
                byte[] data = Arrays.copyOf(p.getData(), len);
                SocketAddress src = p.getSocketAddress();
                if (src.equals(target)) {
                    SocketAddress client = lastClient;
                    if (client != null) {
                        s.send(new DatagramPacket(data, data.length, client));
                        bytesRelayed.addAndGet(data.length);
                    }
                    tap(PacketDirection.S2C, data);
                } else {
                    lastClient = src;
                    s.send(new DatagramPacket(data, data.length, target));
                    bytesRelayed.addAndGet(data.length);
                    tap(PacketDirection.C2S, data);
                }
            } catch (IOException e) {
                if (!running || s.isClosed()) {
                    return;
                }
            }
        }
    }

    private void tap(PacketDirection dir, byte[] data) {
        Socks5Proxy.PacketTap t = tap;
        if (t == null) {
            return;
        }
        try {
            t.onBytes(dir, data);
        } catch (RuntimeException e) {
            // 观察者异常不影响转发
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭失败无影响
            }
        }
    }
}