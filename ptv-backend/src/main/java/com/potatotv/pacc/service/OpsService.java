package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ClientCrashReport;
import com.potatotv.pacc.domain.ClientTelemetry;
import com.potatotv.pacc.domain.RemoteConfig;
import com.potatotv.pacc.repository.ClientCrashReportRepository;
import com.potatotv.pacc.repository.ClientTelemetryRepository;
import com.potatotv.pacc.repository.RemoteConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v4.7 自动化运维：客户端崩溃/性能上报落库、聚合概览与远程配置。
 */
@Service
@RequiredArgsConstructor
public class OpsService {

    private final ClientCrashReportRepository crashRepository;
    private final ClientTelemetryRepository telemetryRepository;
    private final RemoteConfigRepository configRepository;

    /** 崩溃上报落库。 */
    public ClientCrashReport reportCrash(String pteid, String clientVersion, String os, String arch,
                                         String platform, String stackTrace, String contextJson, String controller) {
        ClientCrashReport.Platform p = parsePlatform(platform);
        ClientCrashReport crash = ClientCrashReport.builder()
                .pteid(blankToNull(pteid))
                .clientVersion(clientVersion == null ? "" : clientVersion)
                .os(os == null ? "" : os)
                .arch(arch == null ? "" : arch)
                .platform(p)
                .stackTrace(stackTrace)
                .contextJson(contextJson)
                .controller(blankToNull(controller))
                .build();
        return crashRepository.save(crash);
    }

    /** 性能上报落库。 */
    public ClientTelemetry reportTelemetry(String pteid, String clientVersion, String os,
                                           double cpuPercent, long memMb,
                                           Double fpsImpactPercent, Long detectionLatencyMs) {
        ClientTelemetry t = ClientTelemetry.builder()
                .pteid(blankToNull(pteid))
                .clientVersion(clientVersion == null ? "" : clientVersion)
                .os(os == null ? "" : os)
                .cpuPercent(cpuPercent)
                .memMb(memMb)
                .fpsImpactPercent(fpsImpactPercent)
                .detectionLatencyMs(detectionLatencyMs)
                .build();
        return telemetryRepository.save(t);
    }

    public List<ClientCrashReport> recentCrashes(int limit) {
        if (limit <= 0) limit = 20;
        return crashRepository.findTop200ByOrderByCreatedAtDesc().stream().limit(Math.min(limit, 200)).toList();
    }

    public List<ClientTelemetry> recentTelemetry(int limit) {
        if (limit <= 0) limit = 50;
        return telemetryRepository.findTop2000ByOrderByCreatedAtDesc().stream().limit(Math.min(limit, 2000)).toList();
    }

    /** 运维概览：崩溃总数 / 近 24h 崩溃 / 性能聚合。 */
    public Map<String, Object> overview() {
        long totalCrashes = crashRepository.count();
        long crash24h = crashRepository.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS));
        List<ClientTelemetry> sample = recentTelemetry(2000);
        TelemetryAverage avg = average(sample);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_crashes", totalCrashes);
        m.put("crash_count_last_24h", crash24h);
        m.put("avg_cpu", round1(avg.avgCpu));
        m.put("avg_mem_mb", round1(avg.avgMemMb));
        m.put("sample_telemetry_count", avg.count);
        return m;
    }

    /** 远程配置全部。 */
    public List<RemoteConfig> listConfig() {
        return configRepository.findAllByOrderByCategoryAscIdAsc();
    }

    /** 更新单条配置（upsert）。 */
    public RemoteConfig upsertConfig(String key, String category,
                                     Integer intValue, Double doubleValue, Boolean boolValue, String updatedBy) {
        RemoteConfig.Category c = parseCategory(category);
        Optional<RemoteConfig> existing = configRepository.findById(key);
        RemoteConfig cfg = existing.map(e -> {
            e.setCategory(c);
            e.setIntValue(intValue);
            e.setDoubleValue(doubleValue);
            e.setBoolValue(boolValue);
            e.setUpdatedBy(blankToNull(updatedBy));
            e.setUpdatedAt(Instant.now());
            return e;
        }).orElseGet(() -> RemoteConfig.builder()
                .id(key)
                .category(c)
                .intValue(intValue)
                .doubleValue(doubleValue)
                .boolValue(boolValue)
                .updatedBy(blankToNull(updatedBy))
                .build());
        return configRepository.save(cfg);
    }

    /** 生效配置映射（key → 唯一有效值），供客户端/紧急调整，仅含 bool/int/double 之一。 */
    public Map<String, Object> activeConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (RemoteConfig c : configRepository.findAll()) {
            int values = (c.getIntValue() == null ? 0 : 1)
                    + (c.getDoubleValue() == null ? 0 : 1)
                    + (c.getBoolValue() == null ? 0 : 1);
            if (values != 1) continue;
            if (c.getIntValue() != null) m.put(c.getId(), c.getIntValue());
            else if (c.getDoubleValue() != null) m.put(c.getId(), c.getDoubleValue());
            else m.put(c.getId(), c.getBoolValue());
        }
        return m;
    }

    /**
     * 性能聚合纯函数：计算样本均 CPU / 平均内存；空样本返回 0。
     */
    public static TelemetryAverage average(List<ClientTelemetry> samples) {
        if (samples == null || samples.isEmpty()) return new TelemetryAverage(0, 0, 0);
        double cpu = 0;
        double mem = 0;
        for (ClientTelemetry t : samples) {
            cpu += t.getCpuPercent();
            mem += t.getMemMb();
        }
        int n = samples.size();
        return new TelemetryAverage(cpu / n, mem / n, n);
    }

    public record TelemetryAverage(double avgCpu, double avgMemMb, long count) { }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ClientCrashReport.Platform parsePlatform(String p) {
        if (p == null) return ClientCrashReport.Platform.WINDOWS;
        try {
            return ClientCrashReport.Platform.valueOf(p.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ClientCrashReport.Platform.WINDOWS;
        }
    }

    private static RemoteConfig.Category parseCategory(String c) {
        if (c == null) return RemoteConfig.Category.DETECTION;
        try {
            return RemoteConfig.Category.valueOf(c.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RemoteConfig.Category.DETECTION;
        }
    }
}