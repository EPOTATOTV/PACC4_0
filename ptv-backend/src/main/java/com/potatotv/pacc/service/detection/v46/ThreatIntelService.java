package com.potatotv.pacc.service.detection.v46;

import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v4.6 威胁情报平台：可疑样本静态指纹采集 → 哈希聚类归族 → 自动生成检测规则。
 * 规则经人工审核（主动学习回流）后可提升为正式特征库。
 */
@Service
@RequiredArgsConstructor
public class ThreatIntelService {

    private final ThreatIntelSampleRepository repository;

    /**
     * 录入一条可疑样本并自动归族、生成规则。
     */
    public ThreatIntelSample ingest(String pteid, String edition, String md5, String sha1,
                                    Map<String, String> staticDims) {
        String family = deriveFamily(sha1 != null ? sha1 : md5);
        String rule = generateRule(family, staticDims);
        ThreatIntelSample sample = repository.save(ThreatIntelSample.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .pteid(pteid)
                .edition(edition == null ? "JAVA" : edition)
                .md5(md5)
                .sha1(sha1)
                .family(family)
                .staticDims(staticDims == null ? "{}" : compact(staticDims))
                .generatedRule(rule)
                .build());
        return sample;
    }

    /** 由哈希前缀派生聚类族名（前 8 位 hex 作为粗粒度族）。 */
    static String deriveFamily(String hash) {
        if (hash == null || hash.isBlank()) return "FAM_UNKNOWN";
        String h = hash.toLowerCase();
        int take = Math.min(8, h.length());
        return "FAM_" + h.substring(0, take);
    }

    /** 生成检测规则表达式（JSON 文本）。 */
    static String generateRule(String family, Map<String, String> dims) {
        StringBuilder sb = new StringBuilder("{\"family\":").append(quote(family)).append(",\"checks\":[");
        int i = 0;
        if (dims != null) {
            for (Map.Entry<String, String> e : dims.entrySet()) {
                if (i++ > 0) sb.append(',');
                sb.append("{\"k\":").append(quote(e.getKey()))
                        .append(",\"v\":").append(quote(e.getValue()))
                        .append(",\"op\":\"eq\"}");
            }
        }
        return sb.append("]}").toString();
    }

    private static String compact(Map<String, String> dims) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> e : dims.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> {}
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    /** 按族统计（供情报看板）。 */
    public List<ThreatIntelSample> recent() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    public long newCount() {
        return repository.countByStatus(ThreatIntelSample.Status.NEW);
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (byte b : d) h.append(String.format("%02x", b & 0xff));
            return h.toString();
        } catch (Exception e) {
            return "00000000000000000000000000000000";
        }
    }

    public static String checksum(String s) {
        return sha1Hex(s);
    }
}