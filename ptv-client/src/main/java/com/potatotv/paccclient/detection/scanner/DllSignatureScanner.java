package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SignatureResult;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DLL 签名检测（三层架构 §4.2）：对 Minecraft 进程已加载模块做数字签名验证，
 * 统计未签名模块数与黑名单发布者模块数。
 *
 * <p>签名验证依赖 {@link SystemProbe.Capability#SIGNATURE_VERIFY}（由 PaccManager 原生探针提供），
 * 能力不支持或目标进程未运行时直接返回空。系统目录下的微软签名模块通常会出现在结果里且
 * {@code valid=true}，无需白名单过滤；只有在探针真正给出结果时才判定。探测结果为空表示探针不可达，
 * 此时只写 0 值特征、绝不产事件（文档 §9 注意事项 2：不把「查不了」当「没作弊」）。</p>
 *
 * <p>产出扩展特征（{@code ext_sys_}*），供 {@code dll_signature_anomaly} PRL 规则读取。</p>
 */
public final class DllSignatureScanner implements Detector {

    private static final String ID = "dll_signature_scanner";
    /** 签名验证开销中等，60s 一次（三层架构 §4.2）。 */
    private static final long INTERVAL_MS = 60_000L;

    /** 目标进程（基岩版），与 {@link ModuleScanner} 保持同一常量。 */
    private static final String BEDROCK_PROCESS = "Minecraft.Windows.exe";

    /** 未签名模块数达到该值判定异常（文档 §4.2）。 */
    private static final int UNSIGNED_THRESHOLD = 3;
    /** 单个黑名单发布者模块的权重。 */
    private static final int BLACKLIST_WEIGHT = 40;
    /** 每个未签名模块的权重。 */
    private static final int UNSIGNED_WEIGHT = 5;
    /** 未签名模块计权上限（超过部分不再加分）。 */
    private static final int UNSIGNED_CAP = 5;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        SystemProbe probe = ctx.systemProbe();
        if (!probe.isSupported(SystemProbe.Capability.SIGNATURE_VERIFY)) {
            return Optional.empty();
        }
        if (findProcess(ctx, BEDROCK_PROCESS) == null) {
            // 目标进程未运行：无模块可验
            return Optional.empty();
        }

        List<SignatureResult> results = probe.verifyModuleSignatures(BEDROCK_PROCESS);
        int unsigned = 0;
        List<String> blacklistedModules = new ArrayList<>();
        for (SignatureResult result : results) {
            if (result.valid()) {
                continue;
            }
            unsigned++;
            if (SignatureBlacklist.isBlacklisted(result.publisher())) {
                blacklistedModules.add(result.path());
            }
        }
        int blacklisted = blacklistedModules.size();

        int score = Math.min(100, blacklisted * BLACKLIST_WEIGHT
                + Math.min(unsigned, UNSIGNED_CAP) * UNSIGNED_WEIGHT);
        ctx.putExtended("ext_sys_unsigned_module_count", unsigned);
        ctx.putExtended("ext_sys_blacklisted_publisher", blacklisted);
        mergeScore(ctx, score);

        // 探针不可达（结果为空）时不产事件
        if (results.isEmpty()) {
            return Optional.empty();
        }
        if (unsigned < UNSIGNED_THRESHOLD && blacklisted == 0) {
            return Optional.empty();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("unsigned_modules", unsigned);
        detail.put("blacklisted_modules", blacklistedModules);
        return Optional.of(new DetectionEvent(
                "dll_signature_anomaly",
                blacklisted > 0 ? "high" : "medium",
                score,
                BEDROCK_PROCESS, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /** 同周期多个系统层检测器都写 {@code ext_sys_score} 时取较大值，避免互相覆盖成更小值。 */
    static void mergeScore(DetectContext ctx, int score) {
        double existing = ctx.features().get("ext_sys_score");
        ctx.putExtended("ext_sys_score", Math.max(existing, score));
    }

    private static ProcessSnapshot.ProcessInfo findProcess(DetectContext ctx, String name) {
        for (ProcessSnapshot.ProcessInfo p : ctx.systemProbe().snapshotProcesses().processes()) {
            if (p.name() != null && p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}