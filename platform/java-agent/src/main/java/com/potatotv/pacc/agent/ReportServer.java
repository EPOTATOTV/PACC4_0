package com.potatotv.pacc.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 回环 HTTP 上报服务（{@code jdk.httpserver}）：供同机 ptv-client 轮询 {@code /health} 与 {@code /findings}。
 *
 * <p><b>为何单独成类</b>：{@code com.sun.net.httpserver} 位于 {@code jdk.httpserver} 模块，精简运行时
 * （jlink）可能未包含它。若把 {@link HttpExchange} 类型留在 {@link PaccJavaAgent} 的方法签名中，
 * 探针主类在 {@code premain} 阶段就会因 {@code NoClassDefFoundError} 而无法装载，进而导致
 * <b>{@code -javaagent} 加载失败、JVM 直接中止</b>。将其隔离到本类后，调用方只需 try/catch，
 * 缺失该模块时服务优雅降级，游戏不受影响。</p>
 */
final class ReportServer {

    private ReportServer() {
    }

    /**
     * 启动回环上报服务。
     *
     * @param findings 识别结果通道
     * @param loaded   已装载类计数（{@code /health} 展示）
     * @param captured 已捕获运行时字节码的类数
     * @return 启动是否成功；任何异常均返回 false，绝不抛出
     */
    static boolean start(Findings findings, java.util.concurrent.atomic.AtomicLong loaded, int captured) {
        int port = Integer.parseInt(System.getProperty("pacc.agent.server.port", "17020"));
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/health", ex -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", true);
                body.put("agent", "ptv-java-agent");
                body.put("version", "5.0.0");
                body.put("loaded_classes", loaded.get());
                body.put("captured_classes", captured);
                body.put("pid", ProcessHandle.current().pid());
                respond(ex, 200, Json.encode(body));
            });
            server.createContext("/findings", ex -> respond(ex, 200, findings.snapshotJson()));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            System.out.println("[PTV-JavaAgent] 回环上报服务 127.0.0.1:" + port);
            return true;
        } catch (Throwable t) {
            System.err.println("[PTV-JavaAgent] 回环服务启动失败(不影响检测): " + t);
            return false;
        }
    }

    private static void respond(HttpExchange ex, int code, String body) {
        try {
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        } catch (Throwable ignore) {
            // 单次响应失败忽略
        }
    }
}