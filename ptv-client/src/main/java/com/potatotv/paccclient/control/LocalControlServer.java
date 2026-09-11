package com.potatotv.paccclient.control;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.redscreen.RedscreenReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地回环控制服务：监听 127.0.0.1 随机端口，供桌面壳（Rust/Tauri）下发
 * 检测控制并查询状态/记录/运行时配置。绑定地址仅为回环，请求需携带启动时生成的
 * token 鉴权，防止本机其它进程误操作。端口与 token 写入 control.json 供壳发现。
 * 该通道仅承载检测控制语义，不承载任何玩家数据上报。
 */
public final class LocalControlServer {

    private static final String CONTROL_FILE = "control.json";

    private final HttpServer server;
    private final String token;
    private final DetectionController controller;
    private final String pteid;
    private final String version;

    private LocalControlServer(HttpServer server, String token, DetectionController controller,
                               String pteid, String version) {
        this.server = server;
        this.token = token;
        this.controller = controller;
        this.pteid = pteid;
        this.version = version;
    }

    /** 启动回环控制服务并写入控制文件；失败返回 null（不阻断主流程，保持尽力而为语义）。 */
    public static LocalControlServer start(DetectionController controller, String pteid, String version) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String token = newToken();
            LocalControlServer lcs = new LocalControlServer(server, token, controller, pteid, version);
            server.createContext("/api/local/", lcs::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            lcs.writeControlFile();
            System.out.println("[PTV-Control] 本地控制服务已启动 端口=" + lcs.port());
            return lcs;
        } catch (Exception e) {
            System.err.println("[PTV-Control] 本地控制服务启动失败（不影响主流程）: " + e.getMessage());
            return null;
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void close() {
        server.stop(0);
    }

    private void writeControlFile() throws IOException {
        Path dir = controlDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(CONTROL_FILE),
                Json.encode(Map.of("port", port(), "token", token)), StandardCharsets.UTF_8);
    }

    /** 控制目录：与查端凭据文件同目录（Win: C:\ProgramData\PACC）。 */
    public static Path controlDir() {
        String override = System.getenv("PACC_SCREEN_CRED_FILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override).getParent();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Path.of("C:\\ProgramData\\PACC");
        String home = System.getProperty("user.home");
        return Path.of(home, ".config", "pacc");
    }

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                respond(ex, 401, Json.encode(Map.of("error", "unauthorized")));
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/api/local".length());
            switch (ex.getRequestMethod() + " " + path) {
                case "GET /status" -> respond(ex, 200, statusJson());
                case "GET /pteid" -> respond(ex, 200, Json.encode(Map.of("pteid", pteid)));
                case "POST /start" -> start(ex);
                case "POST /stop" -> stop(ex);
                case "GET /detections" -> detections(ex);
                case "GET /config" -> respond(ex, 200, configJson());
                case "POST /config" -> applyConfig(ex);
                default -> respond(ex, 404, Json.encode(Map.of("error", "not_found")));
            }
        } catch (Exception e) {
            respond(ex, 500, Json.encode(Map.of("error", e.getMessage())));
        }
    }

    private boolean authorized(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        return q != null && ("token=" + token).equals(q);
    }

    private void start(HttpExchange ex) throws IOException {
        if (!controller.isRunning()) {
            controller.start();
        }
        respond(ex, 200, Json.encode(Map.of("running", controller.isRunning())));
    }

    private void stop(HttpExchange ex) throws IOException {
        if (controller.isRunning()) {
            controller.stop();
        }
        respond(ex, 200, Json.encode(Map.of("running", false)));
    }

    private void detections(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        int limit = 0;
        if (q != null && q.contains("limit=")) {
            try {
                limit = Integer.parseInt(q.replaceAll("(?s).*limit=([0-9]+).*", "$1"));
            } catch (Exception ignored) {
                limit = 0;
            }
        }
        List<String> items = controller.recentDetections(limit);
        respond(ex, 200, "[" + String.join(",", items) + "]");
    }

    private void applyConfig(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> src = Json.decodeObject(body);
            DetectionController.RuntimeConfig cfg = controller.cfg();
            Object cr = src.get("client_risk");
            if (cr instanceof Number n) cfg.clientRisk = n.intValue();
            Object hb = src.get("heartbeat_seconds");
            if (hb instanceof Number n) cfg.heartbeatSeconds = Math.max(2, n.doubleValue());
            Object en = src.get("enabled");
            if (en instanceof Boolean b) {
                boolean was = cfg.enabled;
                cfg.enabled = b;
                if (!was && b && !controller.isRunning()) controller.start();
                if (was && !b) controller.stop();
            }
            respond(ex, 200, configJson());
        } catch (Exception e) {
            respond(ex, 400, Json.encode(Map.of("error", e.getMessage())));
        }
    }

    private String statusJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", controller.isRunning());
        m.put("uptime_sec", controller.uptimeSec());
        m.put("pteid", pteid);
        m.put("version", version);
        m.put("heartbeat_seconds", controller.cfg().heartbeatSeconds);
        m.put("client_risk", controller.cfg().clientRisk);
        m.put("enabled", controller.cfg().enabled);
        m.put("detection_count", controller.recentDetections(0).size());
        m.put("last_event_type", controller.lastEventType());
        m.put("redscreen_active", RedscreenReceiver.activeLevel() > 0);
        m.put("redscreen_level", RedscreenReceiver.activeLevel());
        return Json.encode(m);
    }

    private String configJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heartbeat_seconds", controller.cfg().heartbeatSeconds);
        m.put("client_risk", controller.cfg().clientRisk);
        m.put("enabled", controller.cfg().enabled);
        return Json.encode(m);
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}