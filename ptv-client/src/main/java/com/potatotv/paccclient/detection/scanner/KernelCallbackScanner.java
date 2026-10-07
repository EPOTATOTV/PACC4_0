package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.KernelState;
import com.potatotv.paccclient.probe.SignatureResult;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内核回调检测（三层架构 §4.4）：读取 SSDT / IDT hook、未签名驱动与可疑内核回调计数。
 *
 * <p><b>能力边界</b>：SSDT / IDT hook 与内核回调枚举需要 Ring0 驱动配合，用户态探针
 * （{@link KernelState#supported()} 恒 false）拿不到这些数据。此时<b>不写</b>那几项 0 值特征——
 * 否则规则会把「查不了」当成「没有」，正是文档 §9 注意事项 2 要避免的误判——只写
 * {@code ext_sys_score=0}（经取大合并，不会覆盖同周期其它系统层检测器写的更高分）后返回空。</p>
 *
 * <p><b>用户态可得的部分</b>（文档 §4.4 第 3 项「检查内核驱动是否有未签名/可疑驱动」）：
 * 已加载驱动的文件签名走 {@link com.potatotv.paccclient.probe.SystemProbe#verifyFileSignatures}
 * （PaccManager 侧 WinVerifyTrust），能查到就写 {@code ext_sys_unsigned_driver_count}，
 * 并计入本层综合分（每个 20 分，不单独触发事件——目录签名 / 测试签名驱动的判定口径
 * 尚未经真机校准，需要内核模块接入后与 SSDT / 回调数据共同印证）。</p>
 *
 * <p>产出扩展特征（{@code ext_sys_}*），供 {@code kernel_callback} PRL 规则读取。</p>
 */
public final class KernelCallbackScanner implements Detector {

    private static final String ID = "kernel_callback_scanner";
    /** 内核状态变化慢，120s 一次（三层架构 §4.4）。 */
    private static final long INTERVAL_MS = 120_000L;
    /** 单轮最多验证的驱动文件数：控制探针耗时，别把签名验证变成常态负担。 */
    private static final int MAX_DRIVER_FILES = 16;

    private static final int SSDT_WEIGHT = 40;
    private static final int IDT_WEIGHT = 35;
    private static final int UNSIGNED_DRIVER_WEIGHT = 20;
    private static final int CALLBACK_WEIGHT = 15;
    private static final int CALLBACK_CAP = 3;

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
        KernelState state = ctx.systemProbe().kernelState();
        if (!state.supported()) {
            // 用户态下 SSDT / IDT / 回调恒为 0；写 0 会把「查不了」误判成「没有」。
            // 但已加载驱动的签名是用户态能查的（文档 §4.4 第 3 项），单独计入本层综合分。
            int unsignedDrivers = countUnsignedDrivers(ctx);
            if (unsignedDrivers > 0) {
                ctx.putExtended("ext_sys_unsigned_driver_count", unsignedDrivers);
                DllSignatureScanner.mergeScore(ctx, Math.min(100, unsignedDrivers * UNSIGNED_DRIVER_WEIGHT));
            } else {
                DllSignatureScanner.mergeScore(ctx, 0);
            }
            return Optional.empty();
        }

        int score = Math.min(100, state.ssdtHooks() * SSDT_WEIGHT
                + state.idtHooks() * IDT_WEIGHT
                + state.unsignedDriverCount() * UNSIGNED_DRIVER_WEIGHT
                + Math.min(state.suspiciousCallbackCount(), CALLBACK_CAP) * CALLBACK_WEIGHT);
        ctx.putExtended("ext_sys_ssdt_hooks", state.ssdtHooks());
        ctx.putExtended("ext_sys_idt_hooks", state.idtHooks());
        ctx.putExtended("ext_sys_unsigned_driver_count", state.unsignedDriverCount());
        ctx.putExtended("ext_sys_suspicious_callback_count", state.suspiciousCallbackCount());
        DllSignatureScanner.mergeScore(ctx, score);

        boolean hookDetected = state.ssdtHooks() > 0 || state.idtHooks() > 0;
        if (!hookDetected && !(state.unsignedDriverCount() > 0 && state.suspiciousCallbackCount() > 0)) {
            return Optional.empty();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ssdt_hooks", state.ssdtHooks());
        detail.put("idt_hooks", state.idtHooks());
        detail.put("unsigned_drivers", state.unsignedDriverCount());
        detail.put("suspicious_callbacks", state.suspiciousCallbackCount());
        return Optional.of(new DetectionEvent(
                "kernel_callback_anomaly",
                hookDetected ? "critical" : "high",
                score,
                null, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /**
     * 用户态统计未签名驱动：driverquery 的路径 → 签名验证探针。
     * 能力不可用 / 取不到路径时返回 0（调用方不写该维度，避免「查不了」被当成「没有」）。
     */
    private static int countUnsignedDrivers(DetectContext ctx) {
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.SIGNATURE_VERIFY)) {
            return 0;
        }
        List<Path> sysFiles = new ArrayList<>();
        for (DriverSnapshot.DriverInfo driver : ctx.systemProbe().snapshotDrivers().drivers()) {
            String path = driver.path();
            if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".sys")) {
                continue;
            }
            sysFiles.add(Path.of(path));
            if (sysFiles.size() >= MAX_DRIVER_FILES) {
                break;
            }
        }
        if (sysFiles.isEmpty()) {
            return 0;
        }
        int unsigned = 0;
        for (SignatureResult result : ctx.systemProbe().verifyFileSignatures(sysFiles)) {
            if (!result.valid()) {
                unsigned++;
            }
        }
        return unsigned;
    }
}