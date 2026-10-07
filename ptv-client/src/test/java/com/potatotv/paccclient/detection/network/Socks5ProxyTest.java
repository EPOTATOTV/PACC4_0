package com.potatotv.paccclient.detection.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.network.protocol.PacketDirection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * SOCKS5 代理测试（网络代理层 §2.5）。
 *
 * <p>本地起一个 echo 服务，用原生 Socket 走完整握手（method 协商 → CONNECT 127.0.0.1:echoPort），
 * 断言转发成功且 {@code tap} 分别收到 C2S / S2C 字节。全程带超时保护，不依赖外网。</p>
 */
class Socks5ProxyTest {

    @Test
    void socks5转发成功且tap收到双向字节() throws Exception {
        try (ServerSocket echo = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread echoThread = new Thread(() -> echoLoop(echo));
            echoThread.setDaemon(true);
            echoThread.start();

            CountDownLatch c2s = new CountDownLatch(1);
            CountDownLatch s2c = new CountDownLatch(1);
            Socks5Proxy proxy = new Socks5Proxy(0, (dir, data) -> {
                if (dir == PacketDirection.C2S) {
                    c2s.countDown();
                } else {
                    s2c.countDown();
                }
            });
            assertTrue(proxy.start(), "代理应能绑定 127.0.0.1 的临时端口");
            try {
                int proxyPort = proxy.port();
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress("127.0.0.1", proxyPort), 3000);
                    client.setSoTimeout(5000);
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();

                    out.write(new byte[]{0x05, 0x01, 0x00});   // 版本 + 1 种方法 + 无认证
                    out.flush();
                    assertArrayEquals(new byte[]{0x05, 0x00}, in.readNBytes(2));

                    int echoPort = echo.getLocalPort();
                    byte[] connect = {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                            (byte) (echoPort >> 8), (byte) (echoPort & 0xFF)};
                    out.write(connect);
                    out.flush();
                    byte[] reply = in.readNBytes(10);
                    assertEquals(0x05, reply[0] & 0xFF);
                    assertEquals(0x00, reply[1] & 0xFF, "CONNECT 应回 success");

                    byte[] msg = "hello".getBytes(StandardCharsets.US_ASCII);
                    out.write(msg);
                    out.flush();
                    assertArrayEquals(msg, in.readNBytes(msg.length));

                    assertTrue(c2s.await(5, TimeUnit.SECONDS), "tap 应收到 C2S 字节");
                    assertTrue(s2c.await(5, TimeUnit.SECONDS), "tap 应收到 S2C 字节");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    private static void echoLoop(ServerSocket server) {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[128];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // 测试结束 / 连接关闭
        }
    }
}