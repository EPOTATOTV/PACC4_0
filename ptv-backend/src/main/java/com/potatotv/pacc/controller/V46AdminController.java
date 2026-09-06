package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.service.detection.v46.ActiveLearningService;
import com.potatotv.pacc.service.detection.v46.SignatureExpansionService;
import com.potatotv.pacc.service.detection.v46.ThreatIntelAnalystService;
import com.potatotv.pacc.service.detection.v46.ThreatIntelService;
import com.potatotv.pacc.service.detection.v46.ZeroDayAnomalyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * v4.6 检测能力深化管理接口（受 X-Admin-Key 保护）：
 * 零日检测评估 / 威胁情报 / 特征库扩充 / 主动学习复核。
 */
@RestController
@RequestMapping("/api/admin/v46")
@RequiredArgsConstructor
public class V46AdminController {

    private final ZeroDayAnomalyService zeroDayService;
    private final ThreatIntelService threatIntelService;
    private final ThreatIntelAnalystService analystService;
    private final ThreatIntelSampleRepository threatSampleRepository;
    private final SignatureExpansionService expansionService;
    private final ActiveLearningService activeLearningService;

    /** 检测深化看板：零日发现 / 威胁情报 / 特征库扩充 / 主动学习队列统计。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zero_day", Map.of(
                "recent", zeroDayService.repository().findTop50ByOrderByCreatedAtDesc(),
                "open", activeLearningService.pendingZeroDayCount()));
        out.put("threat_intel", Map.of(
                "recent", threatIntelService.recent(),
                "new_count", threatIntelService.newCount()));
        out.put("signature_expansion", Map.of(
                "seed_count", expansionService.seedCount(),
                "seeds", expansionService.seeds()));
        out.put("active_learning", Map.of(
                "pending_zero_day", activeLearningService.pendingZeroDayCount(),
                "pending_threat", activeLearningService.pendingThreatCount(),
                "zero_day_queue", activeLearningService.zeroDayQueue(),
                "threat_queue", activeLearningService.threatQueue()));
        return out;
    }

    /** 零日检测评估（对一组特征执行三路异常融合）。 */
    @PostMapping("/zero-day/assess")
    public ResponseEntity<?> assess(@RequestBody Map<String, Object> body) {
        String pteid = str(body.get("pteid"));
        String edition = str(body.get("edition"));
        FeatureVector fv = featuresOf(body.get("features"));
        if (pteid.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "pteid required"));
        if (fv.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "features required"));
        return ResponseEntity.ok(zeroDayService.assess(pteid, edition.isEmpty() ? "JAVA" : edition, fv));
    }

    /** 复核一条零日发现（主动学习回流）。 */
    @PostMapping("/zero-day/{id}/review")
    public ResponseEntity<?> reviewZeroDay(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Boolean confirmed = bool(body.get("confirmed"));
        if (confirmed == null) return ResponseEntity.badRequest().body(Map.of("error", "confirmed required"));
        ZeroDayFinding f = activeLearningService.reviewFinding(
                id, confirmed, str(body.get("reviewer")), str(body.get("comment")));
        return ResponseEntity.ok(f);
    }

    /** 录入威胁情报样本（哈希聚类 + 规则生成）。 */
    @PostMapping("/threat/ingest")
    public ResponseEntity<?> ingestThreat(@RequestBody Map<String, Object> body) {
        String pteid = str(body.get("pteid"));
        String edition = str(body.get("edition"));
        String md5 = str(body.get("md5"));
        String sha1 = str(body.get("sha1"));
        Map<String, String> dims = dimsOf(body.get("static_dims"));
        if (pteid.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "pteid required"));
        ThreatIntelSample s = threatIntelService.ingest(pteid,
                edition.isEmpty() ? "JAVA" : edition, md5, sha1, dims);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sample_id", s.getId());
        out.put("family", s.getFamily());
        out.put("generated_rule", s.getGeneratedRule());
        out.put("matches", expansionService.match(dims));
        return ResponseEntity.ok(out);
    }

    /** 复核威胁情报样本。 */
    @PostMapping("/threat/{id}/review")
    public ResponseEntity<?> reviewThreat(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Boolean confirmed = bool(body.get("confirmed"));
        if (confirmed == null) return ResponseEntity.badRequest().body(Map.of("error", "confirmed required"));
        return ResponseEntity.ok(activeLearningService.reviewThreat(id, confirmed, str(body.get("reviewer"))));
    }

    /** 自动分析单个威胁情报样本（指标提取 / 严重度 / 类型 / 建议）。 */
    @PostMapping("/threat/analyze/{id}")
    public ResponseEntity<?> analyzeThreat(@PathVariable String id) {
        Optional<ThreatIntelSample> o = threatSampleRepository.findById(id);
        if (o.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(analystService.analyze(o.get()));
    }

    /** AI 家族聚类：对最近样本按静态指纹 K-Means 赋族。 */
    @PostMapping("/threat/cluster")
    public ResponseEntity<?> clusterThreat(@RequestBody(required = false) Map<String, Object> body) {
        List<ThreatIntelSample> recent = threatSampleRepository.findTop50ByOrderByCreatedAtDesc();
        int k = body == null ? 3 : Integer.parseInt(body.getOrDefault("k", 3).toString());
        if (recent.isEmpty()) return ResponseEntity.ok(Map.of("analyzed", 0, "clusters", Map.of()));
        Map<String, Object> summary = analystService.cluster(recent, k);
        return ResponseEntity.ok(summary);
    }

    /** 将已确认的威胁情报样本提升为正式特征库（DRAFT），供运营灰度发布。 */
    @PostMapping("/threat/{id}/promote")
    public ResponseEntity<?> promoteThreat(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(activeLearningService.promoteThreat(id, str(body == null ? null : body.get("operator")).isBlank() ? "admin" : str(body.get("operator"))));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 家族聚类分布 + 已赋族样本。 */
    @GetMapping("/threat/clusters")
    public Map<String, Object> clusters() {
        List<ThreatIntelSample> labeled = threatSampleRepository.findByFamilyLabelIsNotNullOrderByCreatedAtDesc();
        Map<String, Long> dist = new LinkedHashMap<>();
        for (ThreatIntelSample s : labeled) dist.merge(s.getFamilyLabel(), 1L, Long::sum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distribution", dist);
        out.put("samples", labeled);
        return out;
    }

    /** 特征库扩充种子清单 + 演示匹配。 */
    @GetMapping("/signatures")
    public Map<String, Object> signatures() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed_count", expansionService.seedCount());
        out.put("seeds", expansionService.seeds());
        out.put("demo_match", expansionService.match(SignatureExpansionService.demoStaticDims()));
        return out;
    }

    private static FeatureVector featuresOf(Object o) {
        FeatureVector fv = new FeatureVector();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) {
                    fv.set(String.valueOf(e.getKey()), n.doubleValue());
                } else if (v != null) {
                    try {
                        fv.set(String.valueOf(e.getKey()), Double.parseDouble(v.toString()));
                    } catch (NumberFormatException ignored) {
                        // 跳过非数值
                    }
                }
            }
        }
        return fv;
    }

    private static Map<String, String> dimsOf(Object o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) {
            for (Map.Entry<?, ?> e : mm.entrySet()) m.put(String.valueOf(e.getKey()),
                    e.getValue() == null ? "" : e.getValue().toString());
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Boolean bool(Object o) {
        return o == null ? null : Boolean.parseBoolean(o.toString());
    }
}