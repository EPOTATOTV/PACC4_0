package com.potatotv.paccclient;

import com.potatotv.paccclient.control.DetectionController;
import com.potatotv.paccclient.control.LocalControlServer;
import com.potatotv.paccclient.detection.DetectionEngine;
import com.potatotv.paccclient.inspect.InspectAgent;
import com.potatotv.paccclient.redscreen.FullScreenRed;
import com.potatotv.paccclient.redscreen.RedscreenReceiver;
import com.potatotv.paccclient.store.MachineFingerprint;
import com.potatotv.paccclient.store.OfflineQueue;
import com.potatotv.paccclient.store.RedScreenStatePersistence;
import com.potatotv.paccclient.ops.OpsClient;
import com.potatotv.paccclient.signature.SignatureSync;
import com.potatotv.paccclient.transport.PaccWireSigner;
import com.potatotv.paccclient.transport.WssReporter;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    /** 与桌面壳/版本元数据保持一致，供本地控制服务状态上报。 */
    private static final String APP_VERSION = "4.2.0";

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
            RedscreenReceiver.markActive(active.level());
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

        // 后台周期采样上报：封装为可被本地控制服务启停的控制器（默认启动驱动，行为不变）
        DetectionController detector = new DetectionController(engine, reporter,
                new DetectionController.RuntimeConfig(cfg.clientRisk, cfg.heartbeatSeconds, true));
        detector.start();

        // 本地回环控制服务：供桌面壳下发检测控制并查询状态/记录/配置（尽力而为，失败不阻断）
        LocalControlServer control = LocalControlServer.start(detector, pteid, APP_VERSION);

        // ---- v4.7 运维客户端：远程配置、崩溃上报、性能上报、特征库热更新（尽力而为，失败不阻断）----
        OpsClient opsClient = new OpsClient(cfg.serverUri, token);
        SignatureSync signatureSync = new SignatureSync(cfg.sigSecret);
        ScheduledExecutorService opsScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-ops").unstarted(r));

        // 未捕获异常兜底：上报崩溃堆栈后退出
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            opsClient.reportCrash(cfg.signatureVersion, osName(), archName(), platformName(),
                    stackOf(e), contextJson(cfg), null);
            System.err.println("[PTV-Client] 未捕获异常: " + e);
        });

        // 周期性能上报
        opsScheduler.scheduleWithFixedDelay(() -> {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            opsClient.reportTelemetry(cfg.signatureVersion, osName(), processCpu(), usedMb, null, null);
        }, 15, Math.max(30, cfg.heartbeatSeconds * 2), TimeUnit.SECONDS);

        // 特征库热更新 + 远程配置拉取
        opsScheduler.scheduleWithFixedDelay(() -> {
            syncSignatures(cfg, signatureSync);
            Map<String, Object> rc = opsClient.fetchRemoteConfig();
            if (!rc.isEmpty()) {
                Object scan = rc.get("scan_interval_sec");
                if (scan instanceof Number n) {
                    // 远程可调整端侧行为；此处以日志反馈，具体采样周期仍由本机配置主导
                    System.out.println("[PTV-Client] 远程配置生效 keys=" + rc.keySet()
                            + " scan_interval_sec=" + n.longValue());
                }
            }
        }, 10, 300, TimeUnit.SECONDS);

        // 常驻运行，Ctrl+C 退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            detector.stop();
            if (control != null) control.close();
            opsScheduler.shutdownNow();
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

    /** 拉取并热更新特征库：先在线校验摘要与签名，失败保持上一份生效规则。 */
    private static void syncSignatures(ClientConfig cfg, SignatureSync signatureSync) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.serverUri
                            + "/api/player/ops/signatures?edition=" + cfg.edition + "&after_version=0"))
                    .header("Authorization", "Bearer " + cfg.token)
                    .GET().timeout(Duration.ofSeconds(6)).build();
            HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) return;
            int before = signatureSync.ruleCount();
            signatureSync.apply(r.body());
            System.out.println("[PTV-Client] 特征库热更新成功 版本=" + signatureSync.version()
                    + " 规则数 " + before + "→" + signatureSync.ruleCount() + " digest=" + signatureSync.digest());
        } catch (Exception e) {
            System.out.println("[PTV-Client] 特征库同步跳过（不影响现有规则）: " + e.getMessage());
        }
    }

    private static String osName() {
        return System.getProperty("os.name", "unknown");
    }

    private static String archName() {
        return System.getProperty("os.arch", "unknown");
    }

    private static String platformName() {
        String os = osName().toLowerCase();
        if (os.contains("win")) return "WINDOWS";
        if (os.contains("mac")) return "OSX";
        return "LINUX";
    }

    /** 以 JVM 进程 CPU 负载近似作为采样值（不可用或首次为负时按 0 处理）。 */
    private static double processCpu() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getProcessCpuLoad();
            if (load < 0) return 0.0;
            return Math.max(0, Math.min(100, load * 100));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String stackOf(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : e.getStackTrace()) sb.append(el.toString()).append('\n');
        return sb.toString();
    }

    private static String contextJson(ClientConfig cfg) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"os\":\"").append(Json.encode(osName())).append("\"");
        sb.append(",\"arch\":\"").append(Json.encode(archName())).append("\"");
        sb.append(",\"edition\":\"").append(Json.encode(cfg.edition)).append("\"");
        sb.append(",\"client_risk\":").append(cfg.clientRisk);
        return sb.append('}').toString();
    }
}