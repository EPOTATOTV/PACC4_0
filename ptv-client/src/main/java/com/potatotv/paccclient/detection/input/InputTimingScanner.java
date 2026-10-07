package com.potatotv.paccclient.detection.input;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.InputSource;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.probe.UsbDevice;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 输入时序检测器（文档 §3.4 / §3.5 / §3.6）：从 {@link InputSource} 取最近输入事件，
 * 算点击间隔熵 / 固定间隔 / 爆发 / 抖动 / 键鼠同步，叠加板载宏设备、宏软件与宏文件痕迹，
 * 多信号加权后产出 {@code input_timing_anomaly} 或 {@code onboard_macro_anomaly}。
 *
 * <p>受 {@link PerfToggles#VISION}（键 {@code "vision"}，默认关闭）控制，关闭时空返回。</p>
 *
 * <p>宏文件扫描每 5 分钟才真扫一次（时间戳缓存），避免每 10s 遍历目录。</p>
 */
public final class InputTimingScanner implements Detector {

    private static final String ID = "input_timing_scanner";
    private static final long INTERVAL_MS = 10_000L;
    /** 分析的最近输入事件上限。 */
    private static final int MAX_EVENTS = 300;
    /** 宏文件扫描缓存时长（5 分钟）。 */
    private static final long MACRO_FILE_CACHE_MS = 5 * 60 * 1000L;
    /** 时序综合分触发阈值。 */
    private static final int TIMING_THRESHOLD = 50;
    /** 板载宏叠加固定间隔的触发阈值。 */
    private static final int MACRO_TRIGGER = 30;
    private static final int HIGH_THRESHOLD = 75;

    private final MacroFileTracer tracer;
    private volatile long lastMacroScanMillis;
    private volatile int cachedMacroFileRecent;

    public InputTimingScanner() {
        this(new MacroFileTracer());
    }

    /** 测试接缝：注入宏文件扫描器，避免碰真实目录。 */
    InputTimingScanner(MacroFileTracer tracer) {
        this.tracer = tracer;
    }

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
        if (!PerfToggles.enabled(PerfToggles.VISION)) {
            return Optional.empty();
        }
        Optional<InputSource> source = ctx.inputSource();
        if (source.isEmpty()) {
            return Optional.empty();
        }
        List<InputEvent> events = source.get().inputEvents();
        InputTimingAnalyzer.TimingStats stats = InputTimingAnalyzer.analyze(events, MAX_EVENTS);
        if (stats.clickCount() == 0) {
            // 没有点击数据 → 不产出，避免凭空的低熵 / 同步率误报
            return Optional.empty();
        }

        boolean macroDevice = detectMacroDevice(ctx);
        boolean macroSoftware = detectMacroSoftware(ctx);
        int macroFileRecent = recentMacroFiles();

        ctx.putExtended("ext_input_click_entropy", stats.clickEntropy());
        ctx.putExtended("ext_input_fixed_interval", stats.fixedInterval() ? 1 : 0);
        ctx.putExtended("ext_input_burst_pattern", stats.burstPattern() ? 1 : 0);
        if (stats.clickJitterMs() >= 0) {
            // 无数据（-1）时不写负值，缺该键即表示无数据
            ctx.putExtended("ext_input_click_jitter_ms", stats.clickJitterMs());
        }
        ctx.putExtended("ext_input_key_click_sync", stats.keyClickSync());

        int timingScore = timingScore(stats);
        ctx.putExtended("ext_input_timing_score", timingScore);

        int macroScore = OnboardMacroDetector.score(macroDevice, macroSoftware,
                stats.fixedInterval(), stats.burstPattern(), stats.clickJitterMs(), stats.keyClickSync());
        ctx.putExtended("ext_macro_device_score", macroScore);
        ctx.putExtended("ext_macro_file_recent", macroFileRecent);

        boolean timingAnomaly = timingScore >= TIMING_THRESHOLD;
        boolean macroAnomaly = macroScore >= MACRO_TRIGGER && stats.fixedInterval();
        if (!timingAnomaly && !macroAnomaly) {
            return Optional.empty();
        }

        String eventType;
        int score;
        if (macroAnomaly && (!timingAnomaly || macroScore > timingScore)) {
            eventType = "onboard_macro_anomaly";
            score = macroScore;
        } else {
            eventType = "input_timing_anomaly";
            score = timingScore;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("click_entropy", stats.clickEntropy());
        detail.put("fixed_interval", stats.fixedInterval());
        detail.put("burst_pattern", stats.burstPattern());
        detail.put("click_jitter_ms", stats.clickJitterMs());
        detail.put("key_click_sync", stats.keyClickSync());
        detail.put("timing_score", timingScore);
        detail.put("macro_device_score", macroScore);
        detail.put("macro_file_recent", macroFileRecent);
        detail.put("macro_capable_device", macroDevice);
        detail.put("macro_software_running", macroSoftware);
        return Optional.of(new DetectionEvent(
                eventType,
                score >= HIGH_THRESHOLD ? "high" : "medium",
                score,
                null, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /** 时序综合分：固定间隔 40 + 爆发 25 + 熵低且样本足 25 + 抖动小 20 + 键鼠同步 20，cap 100。 */
    private static int timingScore(InputTimingAnalyzer.TimingStats stats) {
        int score = 0;
        if (stats.fixedInterval()) {
            score += 40;
        }
        if (stats.burstPattern()) {
            score += 25;
        }
        if (stats.clickCount() >= 20 && stats.clickEntropy() < 1.5) {
            score += 25;
        }
        if (stats.clickJitterMs() >= 0 && stats.clickJitterMs() < 1.0) {
            score += 20;
        }
        if (stats.keyClickSync() > 0.8) {
            score += 20;
        }
        return Math.min(100, score);
    }

    private static boolean detectMacroDevice(DetectContext ctx) {
        SystemProbe probe = ctx.systemProbe();
        if (!probe.isSupported(SystemProbe.Capability.USB)) {
            return false;
        }
        for (UsbDevice device : probe.enumerateUsb()) {
            if (MacroHardware.isMacroCapable(device.vidPid())) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectMacroSoftware(DetectContext ctx) {
        ProcessSnapshot snapshot = ctx.systemProbe().snapshotProcesses();
        for (ProcessSnapshot.ProcessInfo process : snapshot.processes()) {
            if (MacroHardware.isMacroSoftware(process.name())) {
                return true;
            }
        }
        return false;
    }

    /** 宏文件命中数（每 5 分钟才真扫一次）。 */
    private int recentMacroFiles() {
        long now = System.currentTimeMillis();
        if (now - lastMacroScanMillis >= MACRO_FILE_CACHE_MS) {
            cachedMacroFileRecent = tracer.scan().size();
            lastMacroScanMillis = now;
        }
        return cachedMacroFileRecent;
    }
}