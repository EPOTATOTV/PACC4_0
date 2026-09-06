package com.potatotv.pacc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * v4.5 DMA/IOMMU 环境巡检与反调试状态的服务端判定。
 * <p>纯函数核心为 {@link #assess(...)}：客户端本地完成 PCIe 设备扫描、IOMMU/
 * ACPI 完整性校验、内存读取模式监控、反调试矩阵自检后，把布尔结果上报到这里，
 * 按权重聚合出环境对抗风险分并分三级：</p>
 * <ul>
 *   <li>LOW：环境干净，仅日志</li>
 *   <li>MEDIUM：IOMMU 未启用或个别调试痕迹，进入深观察</li>
 *   <li>HIGH：DMA 采集卡 / ACPI 被篡改 / 内核调试视图，进入加固 + 告警链路</li>
 * </ul>
 * <p>此服务只负责「判定 + 上报」，高宗固化/聚合由上层 Controller/Service 完成。</p>
 */
@Service
public class CounterMeasureEnvironmentService {

    private final int suspectThreshold;
    private final int highThreshold;

    public CounterMeasureEnvironmentService(
            @Value("${pacc.countermeasure.environment-suspect:30}") int suspectThreshold,
            @Value("${pacc.countermeasure.environment-high:60}") int highThreshold) {
        this.suspectThreshold = suspectThreshold;
        this.highThreshold = highThreshold;
    }

    public enum Level { LOW, MEDIUM, HIGH }

    /** 环境/调试状态上报。findings 为逗号分隔的调试命中项（如 hardware_breakpoint,seh_hooked）。 */
    public record Assessment(int score, Level level, List<String> findings) {
    }

    /** 纯函数判定。null 输入视为干净。 */
    public Assessment assess(boolean iommuEnabled, boolean acpiDmacIntegrity,
                             boolean kernelDebuggerDetected, boolean pcieSuspicious,
                             boolean memoryReadAlert, List<String> antidebugFindings) {
        List<String> out = new ArrayList<>();
        int score = 0;

        if (!iommuEnabled) {
            score += 25;
            out.add("dma:ioommu_disabled");
        }
        if (!acpiDmacIntegrity) {
            score += 35;
            out.add("dma:acpi_tampered");
        }
        if (kernelDebuggerDetected) {
            score += 25;
            out.add("debug:kernel_debugger");
        }
        if (pcieSuspicious) {
            score += 15;
            out.add("dma:pcie_suspicious");
        }
        if (memoryReadAlert) {
            score += 30;
            out.add("dma:memory_read_pattern");
        }
        if (antidebugFindings != null) {
            for (String f : antidebugFindings) {
                if (f == null || f.isBlank()) continue;
                score += 10;
                out.add("debug:" + f.trim());
            }
        }
        score = Math.min(score, 100);
        Level level = score >= highThreshold ? Level.HIGH
                : score >= suspectThreshold ? Level.MEDIUM : Level.LOW;
        return new Assessment(score, level, out);
    }

    public int suspectThreshold() {
        return suspectThreshold;
    }

    public int highThreshold() {
        return highThreshold;
    }

    /** 供管理端展示当前阈值。 */
    public Map<String, Object> configView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environment_suspect", suspectThreshold);
        m.put("environment_high", highThreshold);
        return m;
    }

    /** 便捷：逗号分隔串 → 去空白后的列表。 */
    public static List<String> splitFindings(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Stream.of(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
    }
}