package com.potatotv.paccclient;

import com.potatotv.paccclient.detection.DetectionEngine;
import com.potatotv.paccclient.inspect.InspectAgent;
import com.potatotv.paccclient.redscreen.FullScreenRed;
import com.potatotv.paccclient.redscreen.RedscreenReceiver;
import com.potatotv.paccclient.store.MachineFingerprint;
import com.potatotv.paccclient.store.OfflineQueue;
import com.potatotv.paccclient.store.RedScreenStatePersistence;
import com.potatotv.paccclient.transport.PaccWireSigner;
import com.potatotv.paccclient.transport.WssReporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PACC 玩家端用户态服务入口。
 * <p>流程：加载配置 → 建立 PTV 长连接 → 周期检测并上报 → 接收红屏指令。
 * 该进程独立运行于玩家设备，仅与 PTV 管控服务器通信。</p>
 * <p>入口类：{@code java -jar ptv-client.jar} 即可启动玩家端服务。</p>
 */
public final class PaccClient {

    public static void main(String[] args) {
        ClientConfig cfg = ClientConfig.load();
        System.out.println("[PTV-Client] PACC v4.2 玩家端启动 pteid=" + cfg.pteid
                + " edition=" + cfg.edition + " signature=" + cfg.signatureVersion);

        // 获取访问令牌：演示模式自动登录 PTV 换取真实 JWT，保证 WSS 握手通过
        String pteid = cfg.pteid;
        String token = cfg.token;
        if (cfg.demoLogin) {
            try {
                PtvAuth.Session session = new PtvAuth().login(cfg.serverUri, cfg.identity, cfg.password, cfg.remember);
                token = session.accessToken();
                if (session.pteid() != null && !session.pteid().isEmpty()) {
                    pteid = session.pteid();
                }
                System.out.println("[PTV-Client] 已登录 PTV，获取到真实 JWT pteid=" + pteid);
            } catch (Exception e) {
                System.err.println("[PTV-Client] 自动登录失败，回退使用配置令牌: " + e.getMessage());
            }
        }

        // 本地加密存储：口令 = 设备指纹 + PTEID
        String storePassword = MachineFingerprint.hash() + "|" + pteid;
        Path storeDir = resolveStoreDir();

        // 向本地壳共享查端屏幕共享凭据：桌面 WebView 的 JS 读不到 HttpOnly cookie，
        // 由 Java 客户端（持真实 JWT）写本地文件，Tauri 桥读取后注入 /screen-share 页。
        writeScreenShareCredentials(pteid, token);

        RedScreenStatePersistence redscreenState =
                new RedScreenStatePersistence(storeDir.resolve("redscreen.enc"), storePassword);
        redscreenState.loadActive().ifPresent(active -> {
            System.out.println("[PTV-Client] 检测到未解除红屏，重启恢复 level=" + active.level());
            FullScreenRed.show(active.level(), active.cheatType(), active.masked(), active.risk());
        });
        RedscreenReceiver.init(redscreenState);

        OfflineQueue offlineQueue =
                new OfflineQueue(storeDir.resolve("outbox.enc"), storePassword, 1000, true);

        DetectionEngine engine = new DetectionEngine();
        // 远程查端代理：收到 inspect_* 信令时回传取证；出站经 protobuf 信封二进制帧上报
        InspectAgent inspectAgent = new InspectAgent();
        WssReporter reporter = new WssReporter(pteid, cfg.edition, cfg.buildConnectUri(token, pteid),
                cfg.heartbeatSeconds, cfg.signatureVersion, cfg.reconnectDelaySeconds, cfg.autoReconnect,
                cfg.wssSignSecret, json -> routeMessage(json, inspectAgent), offlineQueue);
        PaccWireSigner wire = new PaccWireSigner(cfg.wssSignSecret, pteid);
        inspectAgent.setResponder(m -> reporter.sendEnvelope(wire.build(
                m.containsKey("type") ? String.valueOf(m.get("type")) : "inspect_started",
                m.get("session_id") instanceof String s ? s : null,
                Json.encode(m)).toByteArray()));

        try {
            reporter.connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[PTV-Client] 启动中断: " + e.getMessage());
            return;
        }

        // 后台周期采样上报
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-detector").unstarted(r));
        scheduler.scheduleWithFixedDelay(() -> {
            engine.sample(cfg.clientRisk).ifPresent(reporter::report);
        }, 2, cfg.heartbeatSeconds, TimeUnit.SECONDS);

        // 常驻运行，Ctrl+C 退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            reporter.close();
            System.out.println("[PTV-Client] 玩家端已退出");
        }));

        // 等待
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 依平台解析本地加密存储目录。 */
    private static Path resolveStoreDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            String base = (appdata != null && !appdata.isBlank()) ? appdata : System.getProperty("user.home");
            return Path.of(base, "PACC");
        }
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "PACC");
        }
        return Path.of(System.getProperty("user.home"), ".config", "pacc");
    }

    /**
     * 写查端屏幕共享凭据文件，供 Tauri 桥 {@code screen_share_credentials} 读取后注入前端。
     * <p>路径与桌面壳默认一致（Win: {@code C:\ProgramData\PACC\ws-credentials.json}），
     * 可用环境变量 {@code PACC_SCREEN_CRED_FILE} 覆盖。JWT 为明文写盘属本机壳内闭环的
     * 必要取舍，仅限本机 WebView 使用；写入失败不阻断主流程。</p>
     */
    private static void writeScreenShareCredentials(String pteid, String token) {
        try {
            String path = System.getenv("PACC_SCREEN_CRED_FILE");
            if (path == null || path.isBlank()) {
                path = System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "C:\\ProgramData\\PACC\\ws-credentials.json"
                        : java.nio.file.Path.of(System.getProperty("user.home"), ".config", "pacc",
                                "ws-credentials.json").toString();
            }
            Path file = Path.of(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.encode(Map.of("pteid", pteid == null ? "" : pteid,
                    "token", token == null ? "" : token)), StandardCharsets.UTF_8);
            System.out.println("[PTV-Client] 已写入查端屏幕共享凭据 " + file);
        } catch (Exception e) {
            System.err.println("[PTV-Client] 写入查端凭据失败（不影响主流程）: " + e.getMessage());
        }
    }

    /** 依消息类型分发给对应处理器：查端信令走 InspectAgent，其余走红屏/缓解处理。 */
    private static void routeMessage(String json, InspectAgent inspectAgent) {
        if (json != null && json.contains("inspect_")) {
            inspectAgent.handle(json);
        } else {
            RedscreenReceiver.handle(json);
        }
    }

    private PaccClient() {
    }
}