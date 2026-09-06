package com.potatotv.pacc.service.detection.v47;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.IocIndicator;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.util.ml.FingerprintClusterer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v4.7 IOC 中心化：将威胁情报样本自动分析/静态指纹中的可观测指标独立抽取为中心化 IOC 库，
 * 支持检索、订阅告警、出示处置（DISARM 误报 / EXPIRED 过期）、命中计数与检测阈值联动。
 */
@Service
@RequiredArgsConstructor
public class IocService {

    private final IocIndicatorRepository repository;
    private final ThreatIntelSampleRepository threatSamples;
    private final ObjectMapper objectMapper;

    /** 从指定威胁样本抽取并入库 IOC（自动分析 indicators + 静态指纹 token + md5/sha1 哈希）。 */
    public List<IocIndicator> importFromSample(String sampleId) {
        ThreatIntelSample s = threatSamples.findById(sampleId)
                .orElseThrow(() -> new NoSuchElementException("sample not found: " + sampleId));
        List<IocIndicator> created = new ArrayList<>();

        if (s.getMd5() != null && !s.getMd5().isBlank()) {
            process(created, s.getMd5(), "FILE_HASH", s);
        }
        if (s.getSha1() != null && !s.getSha1().isBlank()) {
            process(created, s.getSha1(), "FILE_HASH", s);
        }
        if (s.getFamilyLabel() != null && !s.getFamilyLabel().isBlank()) {
            process(created, s.getFamilyLabel(), "CLIENT_FAMILY", s);
        }

        // 自动分析报告里的 indicators 数组
        JsonNode report = readTree(s.getAutoAnalysis());
        if (report != null && report.has("indicators")) {
            for (JsonNode v : report.path("indicators")) {
                String val = v.asText("").trim();
                if (!val.isEmpty()) process(created, val, inferType(val), s);
            }
        }
        // 静态指纹 token 里可疑的末位（.class/.dll/.sys/.so 等）作为 STRING 指标
        for (String tok : FingerprintClusterer.tokenize(parseDims(s.getStaticDims()))) {
            if (isSuspiciousToken(tok)) process(created, tok, "STRING", s);
        }
        return created;
    }

    /** 检索：q 模糊 + type/state 过滤 + 分页。 */
    public Map<String, Object> list(String q, String type, String state, int page, int size) {
        q = blankToNull(q);
        type = blankToNull(type);
        state = blankToNull(state);
        int p = Math.max(0, page);
        int sz = Math.max(1, Math.min(100, size));
        Page<IocIndicator> pageData = repository.search(q, type, state, PageRequest.of(p, sz));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", pageData.getContent());
        out.put("total", pageData.getTotalElements());
        out.put("page", p);
        out.put("size", sz);
        return out;
    }

    /** 订阅告警（subscribed=true，状态回到 OPEN）。 */
    public IocIndicator subscribe(Long id) {
        IocIndicator i = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ioc not found: " + id));
        i.setSubscribed(true);
        if ("DISARMED".equals(i.getState())) i.setState("OPEN");
        return repository.save(i);
    }

    /** 误报出示：标记 DISARMED 并从告警订阅中移除。 */
    public IocIndicator disarm(Long id) {
        IocIndicator i = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ioc not found: " + id));
        i.setState("DISARMED");
        i.setSubscribed(false);
        return repository.save(i);
    }

    /** 命中计数（供检测阈值联动演示）：命中并可能触发告警。 */
    public Map<String, Object> hit(Long id) {
        IocIndicator i = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ioc not found: " + id));
        i.setHitCount(i.getHitCount() + 1);
        i.setLastSeen(Instant.now());
        repository.save(i);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", i.getId());
        out.put("value", i.getValue());
        out.put("hit_count", i.getHitCount());
        out.put("alert_triggered", i.isSubscribed() && i.getHitCount() >= i.getAlertThreshold());
        return out;
    }

    /** 中心化概览：各类型/状态计数 + 订阅数 + 高严重度数。 */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> typeDist = new LinkedHashMap<>();
        repository.findAll().forEach(i -> typeDist.merge(i.getType(), 1L, Long::sum));

        out.put("by_type", typeDist);
        out.put("open", repository.countByState("OPEN"));
        out.put("disarmed", repository.countByState("DISARMED"));
        out.put("subscribed", repository.countBySubscribedTrue());
        out.put("high_severity", repository.countBySeverityGreaterThanEqual(4));
        out.put("total", repository.count());
        return out;
    }

    /** 将下划线写法映射为规范 IOC 类型。 */
    public static String inferType(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.matches("[0-9a-f]{12,64}")) return "FILE_HASH";
        if (v.equals("ghost") || v.equals("inject") || v.equals("loader")) return "STRING";
        if (v.matches("([0-9a-f]{8}-){3}[0-9a-f]{8,}")) return "URL";
        if (v.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?") || v.contains("ip:") || v.contains("endpoint")) return "IP";
        return "STRING";
    }

    private void process(List<IocIndicator> created, String value, String type, ThreatIntelSample src) {
        String v = value.trim();
        if (v.isEmpty()) return;
        // 忽略显然非指标的通用 token
        if (type.equals("STRING") && (v.length() > 64 || v.contains(":") && !v.contains("("))) {
            // 保留较短的语义串，超长视为噪声
            if (v.length() > 64) return;
        }
        IocIndicator row = repository.findByValueAndType(v, type).orElseGet(() -> {
            IocIndicator nw = IocIndicator.builder()
                    .value(v).type(type).sourceId(src.getId())
                    .sourceFamily(src.getFamilyLabel() != null ? src.getFamilyLabel() : src.getFamily())
                    .severity(severityOf(src))
                    .firstSeen(Instant.now()).lastSeen(Instant.now())
                    .build();
            return repository.save(nw);
        });
        row.setLastSeen(Instant.now());
        repository.save(row);
        created.add(row);
    }

    private static boolean isSuspiciousToken(String t) {
        return t.endsWith(".class") || t.endsWith(".dll") || t.endsWith(".so")
                || t.endsWith(".sys") || t.contains("ghost") || t.contains("inject")
                || t.contains("loader") || t.endsWith(".jar");
    }

    private int severityOf(ThreatIntelSample s) {
        JsonNode r = readTree(s.getAutoAnalysis());
        if (r != null && r.has("tier")) {
            return switch (r.path("tier").asText("")) {
                case "HIGH" -> 5;
                case "MEDIUM" -> 4;
                default -> 3;
            };
        }
        return 3;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> parseDims(String staticDims) {
        Map<String, String> out = new LinkedHashMap<>();
        if (staticDims == null || staticDims.isBlank()) return out;
        try {
            Map<?, ?> m = objectMapper.readValue(staticDims, Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : e.getValue().toString());
        } catch (Exception ignored) {
            // 解析失败视为无可解析维度
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}