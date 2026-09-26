package com.potatotv.paccclient;

import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.ai.ModelRepository;
import com.potatotv.paccclient.ai.ModelSync;
import com.potatotv.paccclient.apm.ApmCollector;
import com.potatotv.paccclient.apm.ClientHealthMetrics;
import com.potatotv.paccclient.control.DetectionController;
import com.potatotv.paccclient.control.LocalControlServer;
import com.potatotv.paccclient.detection.DetectionEngine;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.federated.FederatedModelDownlink;
import com.potatotv.paccclient.detection.federated.FederatedSettings;
import com.potatotv.paccclient.detection.federated.GradientUploader;
import com.potatotv.paccclient.detection.federated.LocalGradientTrainer;
import com.potatotv.paccclient.detection.stealth.StealthTelemetry;
import com.potatotv.paccclient.detection.stream.StreamDetectionPipeline;
import com.potatotv.paccclient.inspect.InspectAgent;
import com.potatotv.paccclient.redscreen.FullScreenRed;
import com.potatotv.paccclient.redscreen.RedscreenReceiver;
import com.potatotv.paccclient.redscreen.SessionRecorder;
import com.potatotv.paccclient.security.CodeIntegrityService;
import com.potatotv.paccclient.security.ProcessProtector;
import com.potatotv.paccclient.security.SecurityReporter;
import com.potatotv.paccclient.store.HardwareFingerprintV2;
import com.potatotv.paccclient.store.MachineFingerprint;
import com.potatotv.paccclient.store.OfflineQueue;
import com.potatotv.paccclient.store.RedScreenStatePersistence;
import com.potatotv.paccclient.ops.OpsClient;
import com.potatotv.paccclient.signature.SignatureSync;
import com.potatotv.pacc.proto.PaccWire;
import com.potatotv.paccclient.transport.PaccWireSigner;
import com.potatotv.paccclient.transport.WssReporter;
import com.potatotv.paccclient.transport.WssSessionKey;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
    private static final String APP_VERSION = "5.4.0";

    public static void main(String[] args) {
        ClientConfig cfg = ClientConfig.load();
        System.out.println("[PTV-Client] PACC v5.0 玩家端启动 pteid=" + cfg.pteid
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

        // ---- v5.2 端侧 AI：装载本地模型（下载链路见下方的 ModelSync 周期任务）----
        ModelRepository modelRepository = new ModelRepository();
        LocalAiModel localAi = new LocalAiModel();
        try {
            modelRepository.loadInto(localAi);
        } catch (RuntimeException e) {
            System.err.println("[PTV-Client] 本地模型装载失败（按无模型运行）: " + e.getMessage());
        }
        System.out.println("[PTV-Client] 端侧模型 loaded=" + localAi.loaded()
                + " version=" + localAi.modelVersion());
        // 隐身探针（§4）含系统命令扫描，后台线程预热一次，避免首个心跳被扫描拖慢
        Thread.ofVirtual().name("ptv-stealth-warmup").start(StealthTelemetry::probe);

        DetectionEngine engine = new DetectionEngine(localAi);

        // ---- DF §4.1.1 实时流式检测管线：增量特征 + 滑动窗口 + 两级判定（规则层 <1ms，存疑才进 AI）----
        // 消费线程无事件时 park，空闲不耗 CPU；判定与端到端延迟百分位由管线自身 metrics 暴露。
        StreamDetectionPipeline streamPipeline = new StreamDetectionPipeline(localAi);
        streamPipeline.start();

        // 远程查端代理：收到 inspect_* 信令时回传取证；出站经 protobuf 信封二进制帧上报
        InspectAgent inspectAgent = new InspectAgent();
        WssReporter reporter = new WssReporter(pteid, cfg.edition, cfg.buildConnectUri(token, pteid),
                cfg.heartbeatSeconds, cfg.signatureVersion, cfg.reconnectDelaySeconds, cfg.autoReconnect,
                cfg.wssSignSecret, json -> routeMessage(json, inspectAgent), offlineQueue);
        PaccWireSigner wire = new PaccWireSigner(cfg.wssSignSecret, pteid);
        // ---- WSS 会话级动态密钥（协商 → 轮换 → 断线即弃）----
        // 静态密钥只在握手首帧用一次；之后每条信封都用本连接独有的会话密钥签名。
        // 未启用时 session 为 null，wire 恒用静态密钥，行为与加固前完全一致。
        WssSessionKey session = cfg.wssSessionKeyEnabled ? new WssSessionKey(cfg.wssSignSecret) : null;

        inspectAgent.setResponder(m -> {
            String type = m.containsKey("type") ? String.valueOf(m.get("type")) : "inspect_started";
            String sid = m.get("session_id") instanceof String s ? s : null;
            reporter.sendEnvelope(wire.build(type, sid, Json.encode(m)).toByteArray());
            // 达到轮换阈值（条数或时长）就发 rekey；epoch 要等服务端 ack 后才推进，
            // 否则本地已换密钥、服务端还在用旧的，中间这段消息会全部验签失败。
            if (session != null && session.active() && session.dueForRotation() >= 0) {
                reporter.sendEnvelope(wire.build(WssSessionKey.REKEY_TYPE, session.sessionId(),
                        session.rekeyPayload()).toByteArray());
            }
        });

        if (session != null) {
            reporter.setOnConnected(() -> {
                // 每次连接（含重连）都重新协商：新会话 ID + 新盐，旧密钥不跨连接复用
                String initPayload = session.start();
                wire.setSecret(cfg.wssSignSecret, WssSessionKey.SIG_V1);
                reporter.sendEnvelope(wire.build(WssSessionKey.INIT_TYPE, session.sessionId(),
                        initPayload).toByteArray());
            });
            reporter.setOnBinaryMessage(bytes -> {
                try {
                    PaccWire.WsEnvelope env = PaccWire.WsEnvelope.parseFrom(bytes);
                    if (WssSessionKey.READY_TYPE.equals(env.getType())) {
                        if (session.activate()) {
                            wire.setSecret(session.signingKey(), session.sigVersion());
                            System.out.println("[PTV-Client] WSS 会话密钥已激活 sid=" + session.sessionId());
                        }
                    } else if (WssSessionKey.ACK_TYPE.equals(env.getType())) {
                        Object epoch = Json.decodeObject(env.getPayloadJson()).get("epoch");
                        if (epoch instanceof Number n && session.commitRotation(n.longValue())) {
                            wire.setSecret(session.signingKey(), session.sigVersion());
                            System.out.println("[PTV-Client] WSS 会话密钥已轮换 epoch=" + session.epoch());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[PTV-Client] 会话密钥帧处理失败: " + e.getMessage());
                }
            });
        }

        try {
            reporter.connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[PTV-Client] 启动中断: " + e.getMessage());
            return;
        }
        // 链路已连上：健康维度据此上报 client_wss_connected
        ClientHealthMetrics.SINK.setWssConnected(true);

        // 后台周期采样上报：封装为可被本地控制服务启停的控制器（默认启动驱动，行为不变）
        DetectionController detector = new DetectionController(engine, reporter,
                new DetectionController.RuntimeConfig(cfg.clientRisk, cfg.heartbeatSeconds, true));
        // DF §4.1.1：每个检测事件同时投递进流式管线（无锁入队，不阻塞采样线程）
        detector.attachStreamPipeline(streamPipeline, pteid);
        detector.start();

        // 本地回环控制服务：供桌面壳下发检测控制并查询状态/记录/配置（尽力而为，失败不阻断）
        LocalControlServer control = LocalControlServer.start(detector, pteid, APP_VERSION);

        // ---- v4.7 运维客户端：远程配置、崩溃上报、性能上报、特征库热更新（尽力而为，失败不阻断）----
        OpsClient opsClient = new OpsClient(cfg.serverUri, token);

        // ---- v5.4 APM：系统/游戏/检测/客户端健康四类指标的秒级采样与批量上报 ----
        // 采集器不认识 HTTP，传输经 BatchSink 注入；发送结果回填健康指标（上报成功率/积压条数）。
        ApmCollector apmCollector = new ApmCollector(payload -> opsClient.reportApmBatch(payload));
        apmCollector.start();

        // ---- v5.4 安全：代码完整性、进程自保护、反调试/反注入评估与远程证明（尽力而为）----
        CodeIntegrityService integrityService = new CodeIntegrityService();
        ProcessProtector processProtector = new ProcessProtector();
        ProcessProtector.ProtectionReport protection = processProtector.apply();
        System.out.println("[PTV-Client] 进程自保护 coredump=" + protection.coredumpDisabled()
                + " crashHandler=" + protection.crashHandlerInstalled()
                + " 非守护线程=" + protection.nonDaemonThreads());

        SecurityReporter securityReporter = new SecurityReporter(
                body -> opsClient.reportSecurityEvents(body),
                body -> opsClient.reportSecurityEvents(body));
        securityReporter.setIntegrityService(integrityService);
        securityReporter.setSignSecret(cfg.wssSignSecret);
        String clientConfigHash = integrityService.resolveConfigHash("pacc-client.properties");
        ClientHealthMetrics.SINK.setConfigHash(clientConfigHash);
        securityReporter.setConfigHash(clientConfigHash);
        securityReporter.setAttestationTransport(new SecurityReporter.AttestationTransport() {
            @Override
            public SecurityReporter.Challenge challenge(String codeHash, String configHash) {
                OpsClient.AttestationChallenge c = opsClient.requestAttestationChallenge(
                        ApmCollector.CLIENT_VERSION, ApmCollector.platform(), codeHash, configHash);
                return c == null ? null : new SecurityReporter.Challenge(c.challengeId(), c.nonce());
            }

            @Override
            public String respond(String challengeId, String nonce, String codeHash, String configHash,
                                  Map<String, String> runtimeState, String signature, long elapsedMs) {
                return opsClient.respondAttestation(SecurityReporter.encodeRespondBody(
                        challengeId, nonce, codeHash, configHash, runtimeState, signature, elapsedMs));
            }
        });
        securityReporter.start(60);

        SignatureSync signatureSync = new SignatureSync(cfg.sigSecret);
        ScheduledExecutorService opsScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("ptv-ops").unstarted(r));

        // 未捕获异常兜底：上报崩溃堆栈后退出
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            ClientHealthMetrics.SINK.onCrash();
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

        // v5.2 §2.1.3 模型下发同步：启动 45 秒后首次拉取，之后每 6 小时一次；失败保留现有模型
        ModelSync modelSync = new ModelSync(cfg.serverUri, token, modelRepository);
        opsScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (modelSync.syncOnce(localAi)) {
                    System.out.println("[PTV-Client] 端侧模型已更新 version=" + localAi.modelVersion());
                }
            } catch (Exception e) {
                System.err.println("[PTV-Client] 模型同步异常（保留现有模型）: " + e.getMessage());
            }
        }, 45, 6 * 3600, TimeUnit.SECONDS);

        // ---- DF §4.1.2 端侧联邦学习：本地取样 → 本地训练 → 队列上报；全局模型周期下发 ----
        // 出网的只有梯度（模型增量）与聚合权重，原始特征与事件数据不出设备。
        // 本地训练从零参数出发：既有 API 没把「下发的全局权重向量」暴露成数组，故不做无依据的
        // 伪全局初始化；隐私护栏（范数上限 + 样本数门限）由 FederatedSettings 统一配置。
        FederatedSettings fedSettings = FederatedSettings.fromEnvironment();
        FederatedModelDownlink fedDownlink = new FederatedModelDownlink(cfg.serverUri, token, localAi, fedSettings);
        GradientUploader gradientUploader = fedSettings.uploader(pteid, payload -> {
            // 传输必须抛异常才会触发上传器的退避重试，失败一律抛出，不静默丢弃
            if (!opsClient.submitFederatedUpdate(fedSettings.uploadPath(), Json.encode(payload))) {
                throw new IllegalStateException("梯度上报未被服务端接受");
            }
        });
        gradientUploader.start();
        LocalGradientTrainer gradientTrainer = new LocalGradientTrainer(
                fedSettings.featureDim(), fedSettings.autoencoderHidden());
        Deque<FeatureVector> fedSamples = new ArrayDeque<>();
        final int fedSampleCap = 512;

        // 本地取样：每个心跳取一份真实特征，只在本机留存（有界，满则丢最旧）
        opsScheduler.scheduleWithFixedDelay(() -> {
            try {
                FeatureVector fv = engine.lastFeatures();
                if (fv.size() == 0) {
                    return;
                }
                synchronized (fedSamples) {
                    if (fedSamples.size() >= fedSampleCap) {
                        fedSamples.removeFirst();
                    }
                    fedSamples.addLast(fv);
                }
            } catch (RuntimeException e) {
                System.err.println("[PTV-Client] 联邦取样跳过: " + e.getMessage());
            }
        }, 30, Math.max(30, (long) cfg.heartbeatSeconds), TimeUnit.SECONDS);

        // 本地训练 + 梯度上报：启动 5 分钟后首次，之后每 6 小时一次；样本不足就留到下一轮
        opsScheduler.scheduleWithFixedDelay(() -> {
            try {
                List<FeatureVector> batch;
                synchronized (fedSamples) {
                    batch = new ArrayList<>(fedSamples);
                }
                LocalGradientTrainer.TrainingResult r = gradientTrainer.train(batch);
                if (r.sampleCount() < fedSettings.minSamples()) {
                    return;
                }
                if (gradientUploader.submit(null, r.gradient(), r.sampleCount(), r.loss())) {
                    synchronized (fedSamples) {
                        fedSamples.clear();
                    }
                    System.out.println("[PTV-Client] 本地梯度已入队 样本=" + r.sampleCount()
                            + " loss=" + r.loss());
                }
            } catch (Exception e) {
                System.err.println("[PTV-Client] 联邦训练/上报异常（本地样本保留，不影响检测）: " + e.getMessage());
            }
        }, 300, 6 * 3600, TimeUnit.SECONDS);

        // 聚合模型下发：启动 90 秒后首次（排在 ModelSync 的 45 秒之后），之后每 6 小时一次；
        // 版本相同、维度不符、摘要不一致一律保留上一版模型（FederatedModelDownlink 内部保证）
        opsScheduler.scheduleWithFixedDelay(() -> {
            try {
                FederatedModelDownlink.AdoptionResult ar = fedDownlink.fetchAndAdopt();
                if (ar.accepted()) {
                    System.out.println("[PTV-Client] 联邦聚合模型已装载 version=" + ar.version());
                }
            } catch (Exception e) {
                System.err.println("[PTV-Client] 联邦模型下发异常（保留现有模型）: " + e.getMessage());
            }
        }, 90, 6 * 3600, TimeUnit.SECONDS);

        // v5.2 §7.1 硬件指纹上报：启动 20 秒后一次，之后每 12 小时一次（只上报摘要）
        opsScheduler.scheduleWithFixedDelay(() -> {
            try {
                HardwareFingerprintV2.Snapshot fp = HardwareFingerprintV2.read();
                if (opsClient.reportHardwareFingerprint(fp.fullHash())) {
                    System.out.println("[PTV-Client] 硬件指纹已上报 维度=" + fp.components().size()
                            + " 高稳定维度=" + fp.coverage() + "/" + HardwareFingerprintV2.STABLE_DIMENSIONS);
                }
            } catch (Exception e) {
                System.err.println("[PTV-Client] 硬件指纹上报失败（不影响检测）: " + e.getMessage());
            }
        }, 20, 12 * 3600, TimeUnit.SECONDS);

        // v5.2 §7.3 查端回放：默认关闭（PACC_REPLAY_ENABLED=true 才采集），红屏时导出并加密上传
        SessionRecorder recorder = new SessionRecorder(maskPteid(pteid));
        recorder.startRingBuffer();
        RedscreenReceiver.onActivated((level, alertId) -> recorder.onRedScreen(alertId, recording -> {
            ClientHealthMetrics.SINK.onRedscreen();
            boolean ok = opsClient.uploadReplay(recording.alertId(), recording.cipher(),
                    recording.key(), recording.iv(), recording.frames(), recording.width(),
                    recording.height(), recording.fps(), recording.durationMillis(),
                    recording.plainSize(), recording.sha256());
            System.out.println("[PTV-Client] 查端回放 " + (ok ? "已上传" : "上传失败")
                    + " alert=" + recording.alertId() + " 帧=" + recording.frames()
                    + " 明文=" + recording.plainSize() + "B level=" + level);
        }));

        // 常驻运行，Ctrl+C 退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 先声明正常关闭：退出守卫据此不把 Ctrl+C 记成崩溃
            processProtector.markCleanExit();
            detector.stop();
            streamPipeline.close();
            gradientUploader.close();
            recorder.stop();
            if (control != null) control.close();
            opsScheduler.shutdownNow();
            apmCollector.stop();
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

    /** PTEID 脱敏（回放水印用，与后端 mask 口径一致）。 */
    private static String maskPteid(String pteid) {
        if (pteid == null || pteid.length() < 4) return "****";
        return pteid.substring(0, 2) + "***" + pteid.substring(pteid.length() - 2);
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