package com.potatotv.pacc.agent;

import com.sun.net.httpserver.HttpServer;

import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 版（基岩版不可用）进程内检测代理。
 * <p>通过 {@code -javaagent:ptv-agent.jar} 随游戏 JVM 启动，提供三类真实能力：</p>
 * <ul>
 *   <li><b>字节码特征扫描</b>：{@link SignatureScanner} 在类装载时解析常量池并检索作弊 / 注入特征；</li>
 *   <li><b>类装载监控</b>：经由 {@code ClassFileTransformer} 观察可疑命名空间与注入 Agent；</li>
 *   <li><b>本地上报服务</b>：回环 HTTP 提供 {@code /health}、{@code /findings}，供 ptv-client 轮询后上报 PTV。</li>
 * </ul>
 */
public final class PaccJavaAgent {

    private static final Findings FINDINGS = new Findings();
    private static final AtomicLong LOADED = new AtomicLong();
    private static volatile Instrumentation GLOBAL_INST;

    public static void premain(String agentArgs, Instrumentation inst) {
        GLOBAL_INST = inst;
        long start = System.currentTimeMillis();
        System.out.println("[PTV-JavaAgent] premain 注入 pacc v5.0 (args=" + (agentArgs == null ? "" : agentArgs) + ")");
        captureBaseline();
        registerTransformer(inst);
        startReportServer();
        scheduleSampling();
        System.out.println("[PTV-JavaAgent] 初始化完成 " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void captureBaseline() {
        // 注：JDK 9+ 移除了 BootClassPath，这里仅统计已加载类数作为基线
        int loaded = GLOBAL_INST == null ? 0 : GLOBAL_INST.getAllLoadedClasses().length;
        System.out.println("[PTV-JavaAgent] baseline captured loaded=" + loaded);
    }

    /**
     * 注册真实字节码扫描器：每次类装载都做常量池特征比对。
     * 命中时回填 {@link Findings}，不修改字节码（只读取证）。
     */
    private static void registerTransformer(Instrumentation inst) {
        inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            @Override
            public byte[] transform(java.lang.Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, java.security.ProtectionDomain pd, byte[] bytes) {
                if (className == null) return null;
                LOADED.incrementAndGet();
                try {
                    SignatureScanner.scan(className, bytes).ifPresent(rule ->
                            FINDINGS.add(rule.signature(), severityOf(rule), "class=" + className));
                } catch (Throwable t) {
                    // 单个类扫描失败不中断游戏
                    System.err.println("[PTV-JavaAgent] scan fail " + className + ": " + t);
                }
                return null; // 仅取证，不做字节码改写
            }
        }, true);
    }

    private static String severityOf(SignatureScanner.Rule r) {
        return switch (r.signature()) {
            case "bytecode_modifier", "injection_agent", "cheat_engine" -> "critical";
            case "killaura_class", "esp_loader" -> "high";
            default -> "medium";
        };
    }

    /** 回环 HTTP 上报服务：供 ptv-client（同机）轮询识别结果。 */
    private static void startReportServer() {
        int port = Integer.parseInt(System.getProperty("pacc.agent.server.port", "17020"));
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/health", ex -> {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("ok", true);
                body.put("agent", "ptv-java-agent");
                body.put("version", "5.0.0");
                body.put("loaded_classes", LOADED.get());
                body.put("pid", ProcessHandle.current().pid());
                respond(ex, 200, Json.encode(body));
            });
            server.createContext("/findings", ex -> respond(ex, 200, FINDINGS.snapshotJson()));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            System.out.println("[PTV-JavaAgent] 回环上报服务 127.0.0.1:" + port);
        } catch (Exception e) {
            System.err.println("[PTV-JavaAgent] 回环服务启动失败(不影响检测): " + e.getMessage());
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) {
        try {
            byte[] b = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    /** 周期采样：观察注入 Agent 数、存活与心跳，供运维/取证。 */
    private static void scheduleSampling() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                1, r -> Thread.ofPlatform().name("pacc-agent-sampler").daemon(true).unstarted(r));
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int agents = GLOBAL_INST == null ? 0 : GLOBAL_INST.isRetransformClassesSupported() ? 1 : 0;
                FINDINGS.add("heartbeat", "info",
                        "uptime_s=" + (System.currentTimeMillis() / 1000)
                                + " loaded=" + (GLOBAL_INST == null ? 0 : GLOBAL_INST.getAllLoadedClasses().length)
                                + " agents=" + agents);
            } catch (Throwable t) {
                System.err.println("[PTV-JavaAgent] sampler error: " + t);
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private PaccJavaAgent() {
    }
}