package com.potatotv.pacc.service.detection.v46;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.SignatureRepository;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * v4.6 概念漂移 / 主动学习：将低置信的零日发现与威胁情报样本放入运营队列，
 * 人工复核（确认真样本 / 误报）后固化结论并回流，作为后续模型与特征库更新的训练信号。
 */
@Service
@RequiredArgsConstructor
public class ActiveLearningService {

    private final ZeroDayFindingRepository zeroDayFindings;
    private final ThreatIntelSampleRepository threatIntelSamples;
    private final SignatureRepository signatures;
    private final ObjectMapper objectMapper;
    private final ThreatIntelService threatIntelService;

    private static final String LIBRARY_VERSION = "4.6.0";

    /** 零日发现复核队列（含低置信待判定样本）。 */
    public List<ZeroDayFinding> zeroDayQueue() {
        return zeroDayFindings.findByStatusOrderByCreatedAtDesc(ZeroDayFinding.Status.OPEN);
    }

    /** 威胁情报样本复核队列。 */
    public List<ThreatIntelSample> threatQueue() {
        return threatIntelSamples.findByStatusOrderByCreatedAtDesc(ThreatIntelSample.Status.NEW);
    }

    /** 审核一条零日发现。 */
    public ZeroDayFinding reviewFinding(String findingId, Boolean confirmed, String reviewer, String comment) {
        ZeroDayFinding f = zeroDayFindings.findById(findingId).orElseThrow();
        f.setStatus(ZeroDayFinding.Status.REVIEWED);
        f.setConfirmed(confirmed);
        f.setReviewer(reviewer);
        f.setReviewedAt(Instant.now());
        f.setReviewComment(comment);
        return zeroDayFindings.save(f);
    }

    /**
     * 将零日发现确认为真样本并回流为威胁情报样本（检测 → 情报闭环）。
     * 特征摘要自动转为静态维度，哈希派生 md5/sha1，复用威胁情报入库链路（归族 + 规则生成）。
     *
     * @return {finding_id, sample_id?, family?, generated_rule?}；无特征摘要时仅返回 finding_id
     */
    public Map<String, Object> reflowFinding(String findingId, String reviewer) {
        ZeroDayFinding f = zeroDayFindings.findById(findingId)
                .orElseThrow(() -> new NoSuchElementException("finding not found: " + findingId));
        f.setStatus(ZeroDayFinding.Status.REVIEWED);
        f.setConfirmed(Boolean.TRUE);
        f.setReviewer(reviewer);
        f.setReviewedAt(Instant.now());
        zeroDayFindings.save(f);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finding_id", f.getId());
        if (f.getFeaturesJson() == null || f.getFeaturesJson().isBlank()) {
            return out;
        }
        Map<String, String> dims = parseFeatureJson(f.getFeaturesJson());
        String md5 = ThreatIntelService.checksum(f.getId()).substring(0, 32);
        String sha1 = ThreatIntelService.checksum(f.getFeaturesJson());
        ThreatIntelSample s = threatIntelService.ingest(f.getPteid(), f.getEdition(), md5, sha1, dims);
        out.put("sample_id", s.getId());
        out.put("family", s.getFamily());
        out.put("generated_rule", s.getGeneratedRule());
        return out;
    }

    /** 审核一条威胁情报样本。 */
    public ThreatIntelSample reviewThreat(String sampleId, Boolean confirmed, String reviewer) {
        ThreatIntelSample s = threatIntelSamples.findById(sampleId).orElseThrow();
        s.setStatus(ThreatIntelSample.Status.REVIEWED);
        s.setConfirmed(confirmed);
        s.setReviewer(reviewer);
        s.setReviewedAt(Instant.now());
        return threatIntelSamples.save(s);
    }

    /**
     * 将已确认的威胁情报样本提升为正式特征库（DRAFT），由运营灰度发布。
     *
     * @return 新建的 Signature 特征码
     * @throws NoSuchElementException 样本不存在
     * @throws IllegalStateException 样本未确认或无可提升规则
     */
    public Signature promoteThreat(String sampleId, String operator) {
        ThreatIntelSample s = threatIntelSamples.findById(sampleId)
                .orElseThrow(() -> new NoSuchElementException("sample not found: " + sampleId));
        if (!Boolean.TRUE.equals(s.getConfirmed())) {
            throw new IllegalStateException("仅已复核确认的样本可提升为正式特征库");
        }
        String rule = s.getGeneratedRule();
        if (rule == null || rule.isBlank()) {
            throw new IllegalStateException("样本无可提升的检测规则");
        }

        Signature.Edition edition = parseEdition(s.getEdition());
        String pattern = extractPattern(rule);
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException("无法从规则中提取特征维度");
        }

        String name = familyName(s);
        int risk = riskOf(s);
        Signature sig = Signature.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .pattern(pattern)
                .riskLevel(risk)
                .edition(edition)
                .libraryVersion(LIBRARY_VERSION)
                .state("DRAFT")
                .createdBy(operator)
                .createdAt(Instant.now())
                .build();
        return signatures.save(sig);
    }

    public long pendingZeroDayCount() {
        return zeroDayFindings.countByStatus(ZeroDayFinding.Status.OPEN);
    }

    public long pendingThreatCount() {
        return threatIntelSamples.countByStatus(ThreatIntelSample.Status.NEW);
    }

    private static Signature.Edition parseEdition(String edition) {
        try {
            if (edition == null || edition.isBlank()) return Signature.Edition.GENERIC;
            return Signature.Edition.valueOf(edition.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Signature.Edition.GENERIC;
        }
    }

    /** 将零日发现的特征摘要 JSON 转为静态维度 Map（数值一律字符串化）。 */
    private Map<String, String> parseFeatureJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey(), e.getValue() == null ? "" : e.getValue().asText()));
        } catch (Exception ignored) {
            // 解析失败视为无可解析维度
        }
        return out;
    }

    /** 从 generatedRule JSON 提取首个特征维度键作为特征码 pattern。 */
    private String extractPattern(String rule) {
        try {
            JsonNode checks = objectMapper.readTree(rule).path("checks");
            if (checks.isArray() && !checks.isEmpty()) {
                return checks.get(0).path("k").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 特征名称优先取 AI 家族标签或命中特征库名，否则用聚类族名。 */
    private static String familyName(ThreatIntelSample s) {
        if (s.getFamilyLabel() != null && !s.getFamilyLabel().isBlank()) return s.getFamilyLabel();
        if (s.getFamily() != null && !s.getFamily().isBlank()) return s.getFamily();
        return "FAM_UNKNOWN";
    }

    /** 风险等级由自动分析 tier 映射为 1-5；无分析时默认 3。 */
    private int riskOf(ThreatIntelSample s) {
        String a = s.getAutoAnalysis();
        if (a == null || a.isBlank()) return 3;
        try {
            String tier = objectMapper.readTree(a).path("tier").asText("");
            return switch (tier) {
                case "HIGH" -> 5;
                case "MEDIUM" -> 4;
                default -> 3;
            };
        } catch (Exception e) {
            return 3;
        }
    }
}