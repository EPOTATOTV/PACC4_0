package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.repository.SignatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 特征库管理：特征码增删改查、灰度发布（1/10/50/100）、版本回滚。
 * 基岩版 / Java 版分别管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层返回值的 null 分析误报
public class SignatureLibraryService {

    private final SignatureRepository repo;
    private final com.potatotv.pacc.util.SigSigner sigSigner;

    @Transactional
    public Signature add(String name, String pattern, int riskLevel, Signature.Edition edition,
                         String libraryVersion, String operator) {
        Signature s = Signature.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .pattern(pattern)
                .riskLevel(riskLevel)
                .edition(edition)
                .libraryVersion(libraryVersion == null ? "v5.0.0" : libraryVersion)
                .state("DRAFT")
                .createdBy(operator)
                .createdAt(Instant.now())
                .version(1)
                .updatedAt(Instant.now())
                .build();
        return repo.save(s);
    }

    public List<Signature> listByEdition(Signature.Edition edition) {
        return repo.findByEditionAndState(edition, "PUBLISHED");
    }

    /** 按状态列出特征码；state 为空或 ALL 时返回全部（含 DRAFT/GRAY，供发布闭环展示）。 */
    public List<Signature> listByEdition(Signature.Edition edition, String state) {
        if (state == null || state.isBlank() || "ALL".equalsIgnoreCase(state)) {
            return repo.findByEdition(edition);
        }
        return repo.findByEditionAndState(edition, state);
    }

    /** 灰度发布：按比例标记。简化实现直接标记 GRAY/PUBLISHED。状态变更自增 version。 */
    @Transactional
    public void grayRelease(Signature.Edition edition, int percent, String operator) {
        Instant now = Instant.now();
        List<Signature> drafts = repo.findByEditionAndState(edition, "DRAFT");
        drafts.forEach(d -> {
            d.setState(percent >= 100 ? "PUBLISHED" : "GRAY");
            d.setGrayPercent(percent);
            d.setVersion(d.getVersion() + 1);
            d.setUpdatedAt(now);
            repo.save(d);
        });
        log.info("特征库灰度发布 edition={} percent={} counts={} operator={}", edition, percent, drafts.size(), operator);
    }

    /** 回滚到上一稳定版本：将当前 GRAY/PUBLISHED 标记回滚（简化：置 DRAFT），命中项自增 version。 */
    @Transactional
    public int rollback(Signature.Edition edition, String operator) {
        Instant now = Instant.now();
        return repo.markState("PUBLISHED", "DRAFT", edition, now)
                + repo.markState("GRAY", "DRAFT", edition, now);
    }

    public long draftCount() {
        return repo.countByState("DRAFT");
    }

    /**
     * v4.7 增量 diff：返回自 afterVersion 之后新增/变更的特征（afterVersion 为 null 时全量），
     * 并对排序后的 id:pattern:risk:version 计算 SHA-256 整体内容哈希（hex 小写）。
     * 客户端据此可判断增量是否超过 100KB 阈值。
     */
    public Map<String, Object> diff(Signature.Edition edition, Long afterVersion, boolean signed) {
        List<Signature> all = afterVersion == null
                ? repo.findByEdition(edition)
                : repo.findByEditionAndVersionGreaterThan(edition, afterVersion);
        String digest = digest(all);
        String libraryVersion = all.isEmpty()
                ? "v5.0.0" : all.get(0).getLibraryVersion();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("edition", edition.name());
        resp.put("after_version", afterVersion);
        resp.put("count", all.size());
        resp.put("library_version", libraryVersion);
        resp.put("changes", all);
        resp.put("digest", digest);
        if (signed) {
            resp.put("signature", sigSigner.sign(digest + "|" + libraryVersion));
        }
        return resp;
    }

    /** 对 id:pattern:risk:version 排序后拼接做 SHA-256，返回 hex 小写。 */
    private String digest(List<Signature> list) {
        String payload = list.stream()
                .map(s -> s.getId() + ":" + s.getPattern() + ":" + s.getRiskLevel() + ":" + s.getVersion())
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            byte[] out = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("特征库内容哈希计算失败", e);
        }
    }

    /** 自动回滚判定阈值：误报率 > 0.5% 时建议回滚。 */
    public static boolean autoRollbackThreshold(double falsePositiveRate) {
        return falsePositiveRate > 0.5;
    }

    /** 误报率超阈值时回滚到上一稳定版本，返回命中回滚条数；未超阈值返回 0。 */
    @Transactional
    public int autoRollback(Signature.Edition edition, double falsePositiveRate, String operator) {
        if (!autoRollbackThreshold(falsePositiveRate)) {
            return 0;
        }
        return rollback(edition, operator);
    }
}