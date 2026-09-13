package com.potatotv.paccclient.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsClientTest {

    private HttpServer server;
    private final List<Captured> captured = new ArrayList<>();

    private record Captured(String method, String path, String auth, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/player/ops", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(ex.getRequestMethod(), path, auth, body));

            String respJson;
            if (path.endsWith("/config/active")) {
                respJson = "{\"config\":{\"scan_interval_sec\":60,\"redscreen\":true},\"updated_at\":123}";
            } else {
                respJson = "{\"id\":\"ok\"}";
            }
            byte[] resp = respJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpsClient client() {
        int port = server.getAddress().getPort();
        return new OpsClient("http://127.0.0.1:" + port, "demo-token");
    }

    @Test
    void reportsTelemetryWithAuthAndBody() {
        boolean ok = client().reportTelemetry("v5.0.0", "Windows 11", 23.5, 120, 8L, 42L);
        assertTrue(ok);
        assertEquals(1, captured.size());
        Captured c = captured.get(0);
        assertEquals("POST", c.method());
        assertEquals("/api/player/ops/report/telemetry", c.path());
        assertEquals("Bearer demo-token", c.auth());
        assertTrue(c.body().contains("\"cpu_percent\":23.5"));
        assertTrue(c.body().contains("\"mem_mb\":120"));
        assertTrue(c.body().contains("\"client_version\":\"v5.0.0\""));
    }

    @Test
    void reportsCrash() {
        boolean ok = client().reportCrash("v5.0.0", "Windows 11", "amd64", "WINDOWS",
                "java.lang.Error\n\tat Foo.run(Foo.java:1)", null, "IdlePhase");
        assertTrue(ok);
        assertEquals(1, captured.size());
        assertEquals("POST", captured.get(0).method());
        assertEquals("/api/player/ops/report/crash", captured.get(0).path());
        assertTrue(captured.get(0).auth().startsWith("Bearer "));
        assertTrue(captured.get(0).body().contains("\"stack_trace\":\""));
    }

    @Test
    void parsesRemoteConfig() {
        Map<String, Object> cfg = client().fetchRemoteConfig();
        assertNotNull(cfg);
        assertEquals(60, ((Number) cfg.get("scan_interval_sec")).longValue());
        assertEquals(Boolean.TRUE, cfg.get("redscreen"));
    }
}