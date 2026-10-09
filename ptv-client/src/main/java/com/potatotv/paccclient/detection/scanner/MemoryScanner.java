package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.MemoryScanResult;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 用户态内存特征码检测（文档 §4.3）：Java 侧不下直接读进程内存，改由 PaccManager（.NET）
 * 原生探针经回环接口 {@code 127.0.0.1:17020} 完成 {@code OpenProcess / ReadProcessMemory /
 * 特征码比对}，本类只负责按内置特征码发起扫描并汇总结果。
 *
 * <p>探针不可用、平台不支持或目标进程未运行时，写入 {@code ext_memory_supported=0} 并跳过，
 * 绝不把「扫不了」当成「没作弊」（文档 §9 注意事项 2）；只有探针真正执行且命中特征码才上报。</p>
 *
 * <p>产出扩展特征（{@code ext_memory_}*），供 {@code memory_signature} PRL 规则读取。</p>
 */
public final class MemoryScanner implements Detector {

    private static final String ID = "memory_scanner";
    /** 内存扫描开销较大，30s 一次（文档 §8.3）。 */
    private static final long INTERVAL_MS = 30_000L;
    /** 目标进程：基岩版客户端；Java 版由 JavaAgentProbe 覆盖。 */
    private static final String TARGET_PROCESS = "Minecraft.Windows.exe";
    /** 单条特征码命中的权重。 */
    private static final int HIT_WEIGHT = 50;

    /**
     * 内置基线特征码；完整特征库由云端经 {@code SignatureSync} 增量下发（文档 §9 注意事项 5）。
     *
     * @param id      特征码标识（取证标记）
     * @param pattern 特征字节序列
     * @param mask    掩码，{@code 0xFF} 表示该字节必须相等，{@code 0x00} 表示通配
     */
    record MemSignature(String id, byte[] pattern, byte[] mask) {
    }

    private static final List<MemSignature> SIGNATURES = List.of(
            new MemSignature("horion_aimbot",
                    new byte[]{0x48, (byte) 0x8B, 0x05, 0x00, 0x00, 0x00, 0x00, 0x48, (byte) 0x85, (byte) 0xC0},
                    new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00,
                            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}),
            new MemSignature("ce_speedhack",
                    new byte[]{(byte) 0xF3, 0x0F, 0x11, 0x05, 0x00, 0x00, 0x00, 0x00},
                    new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00}),
            new MemSignature("generic_aimbot_lock",
                    new byte[]{0x0F, 0x57, (byte) 0xC0, (byte) 0xF3, 0x0F, 0x11, 0x05,
                            0x00, 0x00, 0x00, 0x00},
                    new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                            0x00, 0x00, 0x00, 0x00, 0x00}));

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
        if (!probe.isSupported(SystemProbe.Capability.MEMORY) || !targetRunning(probe)) {
            ctx.putExtended("ext_memory_supported", 0);
            ctx.putExtended("ext_memory_signature_hits", 0);
            ctx.putExtended("ext_memory_score", 0);
            return Optional.empty();
        }

        boolean supported = false;
        int hits = 0;
        int score = 0;
        List<String> matched = new ArrayList<>();
        for (MemSignature sig : SIGNATURES) {
            MemoryScanResult result = probe.scanMemory(TARGET_PROCESS, sig.pattern(), sig.mask());
            supported |= result.supported();
            if (result.hit()) {
                hits++;
                score += HIT_WEIGHT;
                matched.add(sig.id() + "@" + result.addresses().get(0));
            }
        }

        ctx.putExtended("ext_memory_supported", supported ? 1 : 0);
        ctx.putExtended("ext_memory_signature_hits", hits);
        ctx.putExtended("ext_memory_score", Math.min(100, score));

        if (hits == 0) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("signatures", matched);
        detail.put("score", Math.min(100, score));
        return Optional.of(new DetectionEvent(
                "memory_signature",
                "high",
                Math.min(100, score),
                TARGET_PROCESS, null, matched.get(0), ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    private static boolean targetRunning(SystemProbe probe) {
        ProcessSnapshot snapshot = probe.snapshotProcesses();
        for (ProcessSnapshot.ProcessInfo process : snapshot.processes()) {
            String name = process.name() == null ? "" : process.name().toLowerCase(Locale.ROOT);
            if (name.equals(TARGET_PROCESS.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}