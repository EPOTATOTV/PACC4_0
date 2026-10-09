package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.detection.network.protocol.PacketDirection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 SOCKS5 代理（网络代理层 §2.5）：RFC1928 的 CONNECT-only、无认证（method 0x00）。
 *
 * <p>玩家把 Java 版游戏指向 {@code 127.0.0.1:port}，流量经此代理转发到真实服务器。
 * <b>只转发、不改字节</b>：代理不做任何 MITM 或加解密，两个方向的原始字节在转发的同时交给
 * {@link PacketTap}（由 {@code PaccProxy} 按方向切帧喂解析器）。绑定 127.0.0.1，每连接一个虚拟线程，
 * 线程为守护线程，socket 异常与关闭都安静处理，{@link #start()} 失败只返回 false、绝不抛。</p>
 */
public final class Socks5Proxy {

    /** 字节观察者：接收某方向的一段原始字节（代理不关心内容）。 */
    public interface PacketTap {
        void onBytes(PacketDirection dir, byte[] data);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 8192;

    private final int configuredPort;
    private final PacketTap tap;
    private final AtomicLong bytesRelayed = new AtomicLong();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public Socks5Proxy(int port, PacketTap tap) {
        this.configuredPort = port;
        this.tap = tap;
    }

    /** 绑定 127.0.0.1 并开始接受连接；失败返回 false，不抛异常。 */
    public boolean start() {
        if (running) {
            return true;
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), configuredPort));
            serverSocket = ss;
            running = true;
            Thread accept = new Thread(this::acceptLoop, "pacc-socks5-accept");
            accept.setDaemon(true);
            accept.start();
            return true;
        } catch (IOException | RuntimeException e) {
            running = false;
            closeQuietly(serverSocket);
            serverSocket = null;
            return false;
        }
    }

    /** 停止监听并关闭服务端 socket。 */
    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
    }

    /** 实际监听端口（绑定 0 时返回系统分配的端口）。 */
    public int port() {
        ServerSocket ss = serverSocket;
        return ss != null && !ss.isClosed() ? ss.getLocalPort() : configuredPort;
    }

    public boolean running() {
        return running;
    }

    /** 双向累计转发的字节数。 */
    public long bytesRelayed() {
        return bytesRelayed.get();
    }

    private void acceptLoop() {
        ServerSocket ss = serverSocket;
        if (ss == null) {
            return;
        }
        while (running) {
            try {
                Socket client = ss.accept();
                Thread.startVirtualThread(() -> handle(client));
            } catch (IOException e) {
                if (!running || ss.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();
            if (!handshake(cin, cout)) {
                return;
            }

            byte[] head = readFully(cin, 4);
            if (head == null || (head[0] & 0xFF) != 0x05) {
                return;
            }
            if ((head[1] & 0xFF) != 0x01) {
                writeReply(cout, (byte) 0x07);   // 仅支持 CONNECT
                return;
            }
            int atyp = head[3] & 0xFF;
            String domain = null;
            InetSocketAddress resolved = null;
            switch (atyp) {
                case 0x01 -> {
                    byte[] a = readFully(cin, 4);
                    if (a == null) {
                        return;
                    }
                    resolved = new InetSocketAddress(ipv4(a), 0);
                }
                case 0x03 -> {
                    byte[] l = readFully(cin, 1);
                    if (l == null) {
                        return;
                    }
                    byte[] d = readFully(cin, l[0] & 0xFF);
                    if (d == null) {
                        return;
                    }
                    domain = new String(d, StandardCharsets.US_ASCII);
                }
                case 0x04 -> {
                    byte[] a = readFully(cin, 16);
                    if (a == null) {
                        return;
                    }
                    resolved = new InetSocketAddress(InetAddress.getByAddress(a), 0);
                }
                default -> {
                    writeReply(cout, (byte) 0x08);   // ATYP 不支持
                    return;
                }
            }
            byte[] pb = readFully(cin, 2);
            if (pb == null) {
                return;
            }
            int targetPort = ((pb[0] & 0xFF) << 8) | (pb[1] & 0xFF);

            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            InetSocketAddress endpoint = domain != null
                    ? new InetSocketAddress(domain, targetPort)
                    : new InetSocketAddress(resolved.getAddress(), targetPort);
            upstream.connect(endpoint, CONNECT_TIMEOUT_MS);

            if (!writeReply(cout, (byte) 0x00)) {
                return;
            }
            pump(client, upstream);
        } catch (IOException | RuntimeException e) {
            // 连接异常 / 对端关闭：安静退出
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void pump(Socket client, Socket upstream) {
        Thread c2s = Thread.startVirtualThread(() -> copy(client, upstream, PacketDirection.C2S));
        Thread s2c = Thread.startVirtualThread(() -> copy(upstream, client, PacketDirection.S2C));
        joinQuietly(c2s);
        joinQuietly(s2c);
    }

    private void copy(Socket from, Socket to, PacketDirection dir) {
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);   // 原样转发，不改字节
                out.flush();
                bytesRelayed.addAndGet(n);
                tap(dir, buf, n);
            }
        } catch (IOException e) {
            // 对端关闭 / 超时：忽略
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void tap(PacketDirection dir, byte[] buf, int n) {
        PacketTap t = tap;
        if (t == null) {
            return;
        }
        try {
            t.onBytes(dir, Arrays.copyOf(buf, n));
        } catch (RuntimeException e) {
            // 观察者异常不影响转发
        }
    }

    private static boolean handshake(InputStream in, OutputStream out) throws IOException {
        byte[] head = readFully(in, 2);
        if (head == null || (head[0] & 0xFF) != 0x05) {
            return false;
        }
        byte[] methods = readFully(in, head[1] & 0xFF);
        if (methods == null) {
            return false;
        }
        boolean noAuth = false;
        for (byte m : methods) {
            if (m == 0x00) {
                noAuth = true;
                break;
            }
        }
        out.write(noAuth ? new byte[]{0x05, 0x00} : new byte[]{0x05, (byte) 0xFF});
        out.flush();
        return noAuth;
    }

    private static boolean writeReply(OutputStream out, byte rep) {
        try {
            out.write(new byte[]{0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        if (n <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) {
                return null;
            }
            off += r;
        }
        return out;
    }

    private static String ipv4(byte[] a) {
        return (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF);
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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