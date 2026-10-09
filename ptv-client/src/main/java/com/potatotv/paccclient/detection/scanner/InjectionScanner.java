package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.InjectionReport;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 注入痕迹检测（三层架构 §4.3）：检查 Minecraft 进程的远程线程、可执行读写内存区域、
 * 待处理 APC 与可疑句柄，量化 DLL 注入 / 内存修改的痕迹。
 *
 * <p>数据由 {@link SystemProbe#detectInjection(String)} 经原生探针完成；能力
 * {@link SystemProbe.Capability#INJECTION} 不支持、或探针返回 {@code supported=false} 时，
 * 只写 0 值特征并返回空（文档 §9 注意事项 2：不把「查不了」当「没作弊」）。</p>
 *
 * <p>产出扩展特征（{@code ext_sys_}*），供 {@code injected_client} PRL 规则读取。</p>
 */
public final class InjectionScanner implements Detector {

    private static final String ID = "injection_scanner";
    /** 注入痕迹变化较快，30s 一次（三层架构 §4.3）。 */
    private static final long INTERVAL_MS = 30_000L;

    /** 目标进程（基岩版）。 */
    private static final String TARGET_PROCESS = "Minecraft.Windows.exe";

    /** 各指标的权重。 */
    private static final int REMOTE_THREAD_WEIGHT = 25;
    private static final int EXEC_RW_REGION_WEIGHT = 20;
    private static final int PENDING_APC_WEIGHT = 15;
    private static final int SUSPICIOUS_HANDLE_WEIGHT = 15;

    /** 远程线程数达到该值即单独触发（文档 §4.3）。 */
    private static final int REMOTE_THREAD_THRESHOLD = 3;

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
        if (!probe.isSupported(SystemProbe.Capability.INJECTION)) {
            return Optional.empty();
        }
        InjectionReport report = probe.detectInjection(TARGET_PROCESS);
        if (!report.supported()) {
            return Optional.empty();
        }

        int apc = report.pendingApc() ? 1 : 0;
        int score = Math.min(100, report.remoteThreadCount() * REMOTE_THREAD_WEIGHT
                + report.executableRwRegionCount() * EXEC_RW_REGION_WEIGHT
                + apc * PENDING_APC_WEIGHT
                + report.suspiciousHandleCount() * SUSPICIOUS_HANDLE_WEIGHT);

        ctx.putExtended("ext_sys_remote_thread_count", report.remoteThreadCount());
        ctx.putExtended("ext_sys_exec_rw_region_count", report.executableRwRegionCount());
        ctx.putExtended("ext_sys_pending_apc", apc);
        ctx.putExtended("ext_sys_suspicious_handle_count", report.suspiciousHandleCount());
        DllSignatureScanner.mergeScore(ctx, score);

        boolean coupledEvidence = report.remoteThreadCount() > 0
                && (report.executableRwRegionCount() > 0 || report.suspiciousHandleCount() > 0);
        if (!coupledEvidence && report.remoteThreadCount() < REMOTE_THREAD_THRESHOLD) {
            return Optional.empty();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("remote_threads", report.remoteThreadCount());
        detail.put("exec_rw_regions", report.executableRwRegionCount());
        detail.put("pending_apc", report.pendingApc());
        detail.put("suspicious_handles", report.suspiciousHandleCount());
        return Optional.of(new DetectionEvent(
                "injected_client", "high", score,
                TARGET_PROCESS, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }
}