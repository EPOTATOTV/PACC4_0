package com.potatotv.pacc.cluster;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简阻塞式 Redis 客户端（纯 JDK，RESP2）。仅实现本项目多实例共享所需的命令
 * （ZADD/ZREMRANGEBYSCORE/ZCARD/DEL/HSET/HGETALL/EXPIRE/AUTH），避免引入 spring-data-redis
 * 及其庞大的传递依赖，满足离线零第三方依赖的约束。
 * <p>每次执行打开一条短连接，简单且天然线程安全；限流/OTP 场景下开销可接受。</p>
 */
public final class RedisClient implements Closeable {

    private final String host;
    private final int port;
    private final String password;
    private final int timeoutMs;

    public RedisClient(String host, int port, String password, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.timeoutMs = timeoutMs;
    }

    /** 执行一段 RESP2 命令并返回解析后的结果：Long / String / null / java.util.List. */
    public Object execute(String... args) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            java.io.OutputStream os = socket.getOutputStream();
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            if (!password.isBlank()) {
                os.write(encode(new String[]{"AUTH", password}));
                os.flush();
                readReply(dis); // 认证失败在此抛出
            }
            os.write(encode(args));
            os.flush();
            return readReply(dis);
        } catch (IOException e) {
            throw new IllegalStateException("Redis 命令执行失败: " + host + ":" + port, e);
        }
    }

    private static byte[] encode(String[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, ("*" + args.length + "\r\n"));
        for (String arg : args) {
            byte[] b = arg.getBytes(StandardCharsets.UTF_8);
            write(out, ("$" + b.length + "\r\n"));
            out.writeBytes(b);
            write(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private Object readReply(DataInputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("连接被对端关闭");
        return switch (b) {
            case '+' -> readLine(in);                     // Simple String
            case '-' -> throw new IllegalStateException("Redis 错误: " + readLine(in));
            case ':' -> Long.parseLong(readLine(in));     // Integer
            case '$' -> readBulk(in);                     // Bulk String
            case '*' -> readArray(in);                    // Array
            default -> throw new IOException("未知 RESP 前缀: " + (char) b);
        };
    }

    private String readBulk(DataInputStream in) throws IOException {
        long len = Long.parseLong(readLine(in));
        if (len < 0) return null;
        byte[] buf = in.readNBytes((int) len);
        in.skipNBytes(2); // trailing CRLF
        return new String(buf, StandardCharsets.UTF_8);
    }

    private java.util.List<Object> readArray(DataInputStream in) throws IOException {
        long n = Long.parseLong(readLine(in));
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (long i = 0; i < n; i++) list.add(readReply(in));
        return list;
    }

    private String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // 短连接每次执行即开即关，无长连接句柄可释放。
    }
}