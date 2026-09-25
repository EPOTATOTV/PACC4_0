package com.potatotv.pacc.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 版（基岩版不可用）进程内检测代理。
 *
 * <p>通过 {@code -javaagent:ptv-agent.jar} 随游戏 JVM 启动，提供以下真实能力：</p>
 * <ul>
 *   <li><b>字节码特征扫描</b>：{@link SignatureScanner} 在类装载时解析常量池并检索作弊 / 注入特征（既有能力，保留）；</li>
 *   <li><b>运行时字节码捕获</b>：{@code ClassFileTransformer} 对 10 个监控目标类与作弊标记类<b>只读取证</b>，
 *       把运行时字节码登记到 {@link ClassCaptureRegistry}，供完整性比对与装载审计；</li>
 *   <li><b>字节码完整性比对</b>：{@link BytecodeIntegrityChecker} 对已装载目标类比对类路径原件与运行时字节；</li>
 *   <li><b>Mod 签名校验</b>：{@link ModSignatureVerifier} 校验已装载 mod jar 的 JAR 签名与签名者；</li>
 *   <li><b>类装载审计</b>：{@link ClassLoaderAuditor} 审计自定义装载器、可疑来源与作弊客户端标记类；</li>
 *   <li><b>反射行为采样</b>：{@link RuntimeSampler} 守护线程周期采样真实游戏状态，产出 JSON findings；</li>
 *   <li><b>本地上报服务</b>：回环 HTTP 提供 {@code /health}、{@code /findings}，供 ptv-client 轮询后上报 PTV。</li>
 * </ul>
 *
 * <p><b>与设计文档 §5.1 的偏差（无 ASM）</b>：本探针保持「零第三方依赖」，无法在转换器中改写方法体做
 * 字节码插桩。文档中的「10 个方法插桩点」在本实现中等价替换为「按目标类名匹配 + 运行时字节码捕获 +
 * 反射采样」，仅做取证，<b>永不修改被检测类的字节码</b>（{@code transform} 恒返回 {@code null}）。</p>
 *
 * <p><b>聚合与输出</b>：mod / 类装载 / 完整性三类结果在 {@link #runFullAudit()} 中统一折算为
 * {@link Findings} 条目（复用既有 findings 通道，不新增传输），并随 {@code /findings} 暴露。</p>
 *
 * <p><b>崩溃防护</b>：所有入口（{@code premain}、转换器回调、采样线程）均包裹 try/catch，
 * 任何异常只记录不抛出；重活（jar 校验、全量审计）在守护线程执行，不占用游戏线程。</p>
 */
public final class PaccJavaAgent {

    private static final String VERSION = "5.4.0";

    /** 统一识别结果通道（既有 /findings 输出通道，复用而非新增传输）。 */
    private static final Findings FINDINGS = new Findings();

    /** 运行时字节码登记表，供完整性比对与装载审计。 */
    private static final ClassCaptureRegistry CAPTURES = new ClassCaptureRegistry();

    private static final AtomicLong LOADED = new AtomicLong();
    private static volatile Instrumentation GLOBAL_INST;

    /** 已上报过的审计类 finding 去重键（signature + detail），避免「premain + 装载后复检」重复上报。 */
    private static final java.util.Set<String> EMITTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 去重键上限；超出后仅停止去重，仍然照常上报。 */
    private static final int EMITTED_CAP = 2048;

    public static void premain(String agentArgs, Instrumentation inst) {
        GLOBAL_INST = inst;
        long start = System.currentTimeMillis();
        System.out.println("[PTV-JavaAgent] premain 注入 pacc v" + VERSION
                + " (args=" + (agentArgs == null ? "" : agentArgs) + ")");
        try {
            captureBaseline();
            registerTransformer(inst);
            startReportServer();
            scheduleSampling();
            startFullAudit();
        } catch (Throwable t) {
            System.err.println("[PTV-JavaAgent] premain 初始化异常(已降级，不影响游戏): " + t);
        }
        System.out.println("[PTV-JavaAgent] 初始化完成 " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void captureBaseline() {
        // 注：JDK 9+ 移除了 BootClassPath，这里仅统计已加载类数作为基线
        int loaded = GLOBAL_INST == null ? 0 : GLOBAL_INST.getAllLoadedClasses().length;
        System.out.println("[PTV-JavaAgent] baseline captured loaded=" + loaded);
    }

    /**
     * 注册统一的字节码转换器：既有常量池特征扫描 + 新增运行时字节码捕获。
     * 恒返回 {@code null}，<b>绝不改写被检测类的字节码</b>（无 ASM 只读取证）。
     */
    private static void registerTransformer(Instrumentation inst) {
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, ProtectionDomain pd, byte[] bytes) {
                if (className == null) return null;
                LOADED.incrementAndGet();
                try {
                    // 1) 既有能力：常量池特征扫描（仅在首次定义时执行，重转换不重复扫描以避免重复 finding）
                    if (classBeingRedefined == null) {
                        SignatureScanner.scan(className, bytes).ifPresent(rule ->
                                FINDINGS.add(rule.signature(), severityOf(rule), "class=" + className));
                    }
                    // 2) 新增能力：捕获监控目标类 / 作弊标记类的运行时字节码（重转换同样捕获）
                    captureIfWatched(className, bytes);
                } catch (Throwable t) {
                    System.err.println("[PTV-JavaAgent] transform fail " + className + ": " + t);
                }
                return null; // 仅取证，不做字节码改写
            }
        };
        inst.addTransformer(transformer, true); // canRetransform=true，便于对已装载目标做一次性捕获
    }

    /** 命中监控目标或作弊标记时，登记其运行时字节码。 */
    private static void captureIfWatched(String className, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        List<AgentTargets.Target> targets = AgentTargets.matchClass(className);
        boolean cheat = ClassLoaderAuditor.matchesCheatMarker(className);
        if (targets.isEmpty() && !cheat) return;
        String reason;
        if (targets.isEmpty()) {
            reason = "cheat_marker";
        } else {
            StringBuilder sb = new StringBuilder("target:");
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(targets.get(i).id());
            }
            reason = sb.toString();
        }
        CAPTURES.record(className, reason, bytes.clone());
    }

    private static String severityOf(SignatureScanner.Rule r) {
        return switch (r.signature()) {
            case "bytecode_modifier", "injection_agent", "cheat_engine" -> "critical";
            case "killaura_class", "esp_loader" -> "high";
            default -> "medium";
        };
    }

    /**
     * 启动回环 HTTP 上报服务（委托 {@link ReportServer}，HTTP 类型不进入本类签名，
     * 以便精简运行时缺失 {@code jdk.httpserver} 时仅降级而非让 {@code -javaagent} 加载失败）。
     */
    private static void startReportServer() {
        try {
            ReportServer.start(FINDINGS, LOADED, CAPTURES.size());
        } catch (Throwable t) {
            System.err.println("[PTV-JavaAgent] 回环服务启动失败(不影响检测): " + t);
        }
    }

    /** 周期采样：心跳统计（既有） + 反射行为采样（新增）。 */
    private static void scheduleSampling() {
        ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(
                1, r -> Thread.ofPlatform().name("pacc-agent-heartbeat").daemon(true).unstarted(r));
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                int agents = GLOBAL_INST == null ? 0 : GLOBAL_INST.isRetransformClassesSupported() ? 1 : 0;
                FINDINGS.add("heartbeat", "info",
                        "uptime_s=" + (System.currentTimeMillis() / 1000)
                                + " loaded=" + (GLOBAL_INST == null ? 0 : GLOBAL_INST.getAllLoadedClasses().length)
                                + " captured=" + CAPTURES.size()
                                + " agents=" + agents);
            } catch (Throwable t) {
                System.err.println("[PTV-JavaAgent] heartbeat error: " + t);
            }
        }, 15, 15, TimeUnit.SECONDS);

        long interval = readLongProperty("pacc.agent.sample.interval.ms", 5000L);
        new RuntimeSampler(FINDINGS).start(interval);
    }

    /**
     * 启动统一审计：{@code premain} 时立即在守护线程执行一次；并在启动宽限期后复检一次，
     * 以覆盖「启动后才装载」的 mod jar 与游戏类（premain 阶段 Minecraft 类尚未装载）。
     */
    private static void startFullAudit() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("pacc-agent-audit").daemon(true).unstarted(r));
        executor.submit(PaccJavaAgent::runFullAudit);
        long delay = readLongProperty("pacc.agent.audit.delay.ms", 20000L);
        executor.schedule(PaccJavaAgent::runFullAudit, Math.max(0L, delay), TimeUnit.MILLISECONDS);
    }

    /**
     * 单一 Findings 聚合入口：捕获已装载目标类字节码 → mod 签名校验 → 类装载审计 → 目标类完整性比对。
     * 全流程守护线程执行，任何异常只记录不抛出。
     */
    private static void runFullAudit() {
        try {
            Instrumentation inst = GLOBAL_INST;
            captureLoadedTargets(inst);

            List<ModSignatureVerifier.ModInfo> mods = ModSignatureVerifier.verify(inst);
            int suspiciousMods = 0;
            for (ModSignatureVerifier.ModInfo m : mods) {
                if (!m.suspicious()) continue;
                suspiciousMods++;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("name", m.name());
                detail.put("path", m.path());
                detail.put("sha256", m.sha256());
                detail.put("signed", m.signed());
                detail.put("valid_signature", m.validSignature());
                detail.put("signer", m.signer());
                detail.put("reason", m.reason());
                emitOnce("suspicious_mod", "high", Json.encode(detail));
            }

            List<ClassLoaderAuditor.ClassLoaderInfo> loaders = ClassLoaderAuditor.audit(inst, CAPTURES);
            for (ClassLoaderAuditor.ClassLoaderInfo info : loaders) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("loader", info.loader());
                detail.put("category", info.category());
                detail.put("reasons", info.reasons());
                detail.put("classes", info.suspiciousClasses());
                emitOnce("suspicious_class_loader", "high", Json.encode(detail));
            }

            List<BytecodeIntegrityChecker.BytecodeModification> integrity =
                    BytecodeIntegrityChecker.checkTargets(inst, CAPTURES);
            int modified = 0;
            int unknown = 0;
            for (BytecodeIntegrityChecker.BytecodeModification bm : integrity) {
                switch (bm.status()) {
                    case MODIFIED -> {
                        modified++;
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("class", bm.className());
                        detail.put("original_sha256", bm.originalSha256());
                        detail.put("runtime_sha256", bm.runtimeSha256());
                        detail.put("method_diffs", bm.methodDiffs());
                        detail.put("note", bm.note());
                        emitOnce("bytecode_modified", "critical", Json.encode(detail));
                    }
                    case UNKNOWN_BASELINE -> unknown++;
                    default -> {
                        // EQUAL / NO_RUNTIME_CAPTURE / ERROR：无需上报
                    }
                }
            }
            System.out.println("[PTV-JavaAgent] 审计完成 mods=" + mods.size() + "(suspicious=" + suspiciousMods + ")"
                    + " loaders=" + loaders.size() + " targets_checked=" + integrity.size()
                    + "(modified=" + modified + ", unknown_baseline=" + unknown + ")");
        } catch (Throwable t) {
            System.err.println("[PTV-JavaAgent] 审计异常(已降级): " + t);
        }
    }

    /**
     * 对「已装载」的监控目标类做一次重转换，触发转换器捕获其运行时字节码，
     * 使完整性比对在无游戏运行期也能覆盖已完成装载的目标类。失败逐个降级。
     */
    private static void captureLoadedTargets(Instrumentation inst) {
        if (inst == null || !inst.isRetransformClassesSupported()) return;
        List<Class<?>> targets = new ArrayList<>();
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (!AgentTargets.matchClass(c.getName().replace('.', '/')).isEmpty()) targets.add(c);
            }
        } catch (Throwable t) {
            return;
        }
        if (targets.isEmpty()) return;
        try {
            inst.retransformClasses(targets.toArray(new Class<?>[0]));
        } catch (Throwable t) {
            for (Class<?> c : targets) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable ignore) {
                    // 不可重转换的类跳过
                }
            }
        }
    }

    /**
     * 上报审计类 finding 并去重：相同「signature + detail」在一次进程生命周期内只上报一次，
     * 以免 premain 首次审计与装载后复检产生重复条目。去重键超限后仅停止去重，仍正常上报。
     */
    private static void emitOnce(String signature, String severity, String detail) {
        String key = signature + '\u0000' + detail;
        if (EMITTED.size() < EMITTED_CAP && !EMITTED.add(key)) return;
        FINDINGS.add(signature, severity, detail);
    }

    private static long readLongProperty(String key, long defaultValue) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? defaultValue : Long.parseLong(v.trim());
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * 自检入口（非运行必需）：{@code java -cp target/ptv-agent.jar com.potatotv.pacc.agent.PaccJavaAgent}。
     * 打印目标数、mod 校验摘要与一次完整性比对结果，用于在无游戏环境下验证探针类可正常加载运行。
     */
    public static void main(String[] args) {
        System.out.println("[PTV-JavaAgent] self-test v" + VERSION);
        System.out.println("targets=" + AgentTargets.all().size());
        List<ModSignatureVerifier.ModInfo> mods = ModSignatureVerifier.verify(null);
        long suspicious = mods.stream().filter(ModSignatureVerifier.ModInfo::suspicious).count();
        System.out.println("mods=" + mods.size() + " suspicious=" + suspicious);
        for (ModSignatureVerifier.ModInfo m : mods) {
            System.out.println("  - " + m.name() + " signed=" + m.signed()
                    + " valid=" + m.validSignature() + " suspicious=" + m.suspicious() + " reason=" + m.reason());
        }
        BytecodeIntegrityChecker.BytecodeModification bm =
                BytecodeIntegrityChecker.check("java/lang/String", null, new ClassCaptureRegistry());
        System.out.println("integrity(java/lang/String) status=" + bm.status());
        System.out.println("[PTV-JavaAgent] self-test done");
    }

    private PaccJavaAgent() {
    }
}