package com.potatotv.pacc.service.detection.v46;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.util.ml.FingerprintClusterer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v4.6 威胁情报深化：样本自动分析与 AI 家族聚类。
 *
 * <p>对可疑样本的静态指纹做指标提取、严重度评估、类型标注与特征库匹配建议；
 * 并通过 K-Means 指纹聚类为相似样本赋予语义家族标签（CLUSTER_i），识别"同一外挂/同一作者"。</p>
 */
@Service
@RequiredArgsConstructor
public class ThreatIntelAnalystService {

    private final ThreatIntelSampleRepository repository;
    private final SignatureExpansionService expansionService;
    private final ObjectMapper objectMapper;

    /** 对单个样本执行自动分析并写回 analysis / familyLabel。 */
    public ThreatIntelSample analyze(ThreatIntelSample s) {
        Map<String, String> dims = parseDims(s.getStaticDims());
        List<String> tokens = FingerprintClusterer.tokenize(dims);
        List<SignatureExpansionService.Matched> matches = expansionService.match(dims);
        List<String> indicators = extractIndicators(tokens);
        String type = detectType(tokens);

        int severity = computeSeverity(tokens, matches);
        String tier = severity >= 85 ? "HIGH" : severity >= 70 ? "MEDIUM" : "LOW";
        String summary = matches.isEmpty()
                ? "新样本，未命中现有特征库，建议人工复核后提升"
                : "命中特征库「" + matches.get(0).name() + "」，可自动灰度发布";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("severity", severity);
        report.put("tier", tier);
        report.put("type", type);
        report.put("indicators", indicators);
        report.put("matched_seeds", matches.stream().map(SignatureExpansionService.Matched::name).toList());
        report.put("summary", summary);
        report.put("suggestion", matches.isEmpty() ? "REVIEW" : "NORMAL");

        try {
            s.setAutoAnalysis(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            s.setAutoAnalysis("{\"severity\":" + severity + "}");
        }
        if (s.getFamilyLabel() == null && !matches.isEmpty()) {
            s.setFamilyLabel(matches.get(0).name());
        }
        return repository.save(s);
    }

    /**
     * 对样本集合执行 K-Means 指纹聚类并赋予家族标签。
     *
     * @param samples 待聚类样本（通常为最近录入）
     * @param k       目标家族数
     * @return 聚类摘要 {clusters: {0:n,...}, analyzed: count}
     */
    public Map<String, Object> cluster(List<ThreatIntelSample> samples, int k) {
        List<double[]> rows = new ArrayList<>();
        for (ThreatIntelSample s : samples) {
            rows.add(FingerprintClusterer.vectorOf(
                    FingerprintClusterer.tokenize(parseDims(s.getStaticDims())), 48));
        }
        int[] labels = FingerprintClusterer.kmeans(rows, k, 200, 11L);

        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            ThreatIntelSample s = samples.get(i);
            s.setFamilyLabel("CLUSTER_" + labels[i]);
            repository.save(s);
            sizes.merge(labels[i], 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyzed", samples.size());
        out.put("clusters", sizes);
        return out;
    }

    private Map<String, String> parseDims(String staticDims) {
        Map<String, String> out = new LinkedHashMap<>();
        if (staticDims == null || staticDims.isBlank()) return out;
        try {
            Map<?, ?> m = objectMapper.readValue(staticDims, Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : e.getValue().toString());
            }
        } catch (Exception ignored) {
            // 解析失败视为无可解析维度
        }
        return out;
    }

    private static int computeSeverity(List<String> tokens, List<SignatureExpansionService.Matched> matches) {
        int sev = 40;
        int seedRisk = matches.stream().mapToInt(SignatureExpansionService.Matched::riskLevel).max().orElse(0);
        sev += seedRisk * 8;
        Locale lo = Locale.ROOT;
        if (tokens.stream().anyMatch(t -> t.contains("ghost") || t.contains("inject"))) sev += 18;
        if (tokens.stream().anyMatch(t -> t.endsWith(".sys") || t.endsWith(".dll") || t.contains("driver"))) sev += 14;
        if (tokens.stream().anyMatch(t -> t.contains("killaura") || t.contains("aimbot"))) sev += 12;
        return Math.max(0, Math.min(100, sev));
    }

    private static List<String> extractIndicators(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (t.endsWith(".class") || t.endsWith(".dll") || t.endsWith(".so") || t.endsWith(".sys")
                    || t.contains("ghost") || t.contains("inject") || t.contains("loader")) {
                out.add(t);
            }
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static String detectType(List<String> tokens) {
        if (tokens.stream().anyMatch(t -> t.contains("ghost") || t.contains("inject"))) return "GHOST_CLIENT";
        if (tokens.stream().anyMatch(t -> t.endsWith(".sys") || t.endsWith(".dll") || t.contains("driver"))) return "KERNEL_DRIVER";
        if (tokens.stream().anyMatch(t -> t.endsWith(".class") || t.contains("javaagent"))) return "JAVA_AGENT";
        if (tokens.stream().anyMatch(t -> t.contains("killaura") || t.contains("aimbot"))) return "AIM_ASSIST";
        return "GENERIC";
    }
}