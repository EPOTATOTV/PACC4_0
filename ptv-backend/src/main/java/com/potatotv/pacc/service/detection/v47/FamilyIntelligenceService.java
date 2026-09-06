package com.potatotv.pacc.service.detection.v47;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.util.ml.FingerprintClusterer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v4.7 家族可视化 / 谱系图：对已 AI 聚类的威胁情报样本按 familyLabel 聚合出家族档案，
 * 并基于静态指纹事件相似度（Jaccard）构建家族间谱系（血缘）图。全部实时聚合 ThreatIntelSample，无需新表。
 */
@Service
@RequiredArgsConstructor
public class FamilyIntelligenceService {

    private final ThreatIntelSampleRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 家族档案清单：每个 AI 家族的大小、版本分布、类型分布、严重度均值、首次/最近出现、确认样本数。
     */
    public List<Map<String, Object>> familyOverview() {
        List<ThreatIntelSample> samples = repository.findByFamilyLabelIsNotNullOrderByCreatedAtDesc();
        Map<String, List<ThreatIntelSample>> byFamily = new LinkedHashMap<>();
        for (ThreatIntelSample s : samples) byFamily.computeIfAbsent(s.getFamilyLabel(), k -> new ArrayList<>()).add(s);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<ThreatIntelSample>> e : byFamily.entrySet()) {
            List<ThreatIntelSample> members = e.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("family", e.getKey());
            p.put("size", members.size());
            p.put("editions", distribution(members, m -> m.getEdition() == null ? "?" : m.getEdition()));
            p.put("severity", avgSeverity(members));
            p.put("confirmed", members.stream().filter(m -> Boolean.TRUE.equals(m.getConfirmed())).count());
            p.put("first_seen", members.stream().map(ThreatIntelSample::getCreatedAt).map(Instant.class::cast)
                    .min(Instant::compareTo).orElse(null));
            p.put("last_seen", members.stream().map(ThreatIntelSample::getCreatedAt).map(Instant.class::cast)
                    .max(Instant::compareTo).orElse(null));
            out.add(p);
        }
        return out;
    }

    /** 家族谱系（血缘）图：节点=家族，边=各自静态指纹 token 集合的 Jaccard 相似度（>=0.05 连边）。 */
    public Map<String, Object> familyGraph() {
        List<ThreatIntelSample> samples = repository.findByFamilyLabelIsNotNullOrderByCreatedAtDesc();
        Map<String, List<ThreatIntelSample>> byFamily = new LinkedHashMap<>();
        for (ThreatIntelSample s : samples) byFamily.computeIfAbsent(s.getFamilyLabel(), k -> new ArrayList<>()).add(s);

        Map<String, Set<String>> tokensOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<ThreatIntelSample>> e : byFamily.entrySet()) {
            Set<String> toks = new LinkedHashSet<>();
            for (ThreatIntelSample s : e.getValue()) toks.addAll(FingerprintClusterer.tokenize(parseDims(s.getStaticDims())));
            tokensOf.put(e.getKey(), toks);
        }

        List<String> names = new ArrayList<>(byFamily.keySet());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String n : names) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", n);
            node.put("name", n);
            node.put("size", byFamily.get(n).size());
            nodes.add(node);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                double w = jaccard(tokensOf.get(names.get(i)), tokensOf.get(names.get(j)));
                if (w >= 0.05) {
                    Map<String, Object> link = new LinkedHashMap<>();
                    link.put("source", names.get(i));
                    link.put("target", names.get(j));
                    link.put("value", Math.round(w * 100) / 100.0);
                    links.add(link);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("links", links);
        out.put("family_count", nodes.size());
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private Map<String, Long> distribution(List<ThreatIntelSample> members, java.util.function.Function<ThreatIntelSample, String> f) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (ThreatIntelSample s : members) m.merge(f.apply(s), 1L, Long::sum);
        return m;
    }

    private int avgSeverity(List<ThreatIntelSample> members) {
        if (members.isEmpty()) return 0;
        int sum = 0;
        int n = 0;
        for (ThreatIntelSample s : members) {
            int sev = severityOf(s);
            if (sev > 0) {
                sum += sev;
                n++;
            }
        }
        return n == 0 ? 0 : Math.round((float) sum / n);
    }

    private int severityOf(ThreatIntelSample s) {
        String a = s.getAutoAnalysis();
        if (a == null || a.isBlank()) return 0;
        try {
            return objectMapper.readTree(a).path("severity").asInt(0);
        } catch (Exception e) {
            return 0;
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
}