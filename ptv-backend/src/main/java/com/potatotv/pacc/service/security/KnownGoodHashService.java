package com.potatotv.pacc.service.security;

import com.potatotv.pacc.domain.KnownGoodHash;
import com.potatotv.pacc.repository.KnownGoodHashRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * v5.4 §3.6 已知合法摘要注册表服务。
 *
 * <p>远程证明的信任根在这里：只有注册且 active 的摘要才被 {@link AttestationService} 认作合法。
 * 入库前强制 64 位十六进制（统一小写），避免大小写/空格差异造成「明明登记了却核验不过」。
 * 重复登记（同 kind 同 hash 且 active）不新增行，直接返回既有行，保持幂等。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnownGoodHashService {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final KnownGoodHashRepository repository;

    /** 摘要是否为在册的已知good（空摘要一律 false）。 */
    public boolean isKnownGood(KnownGoodHash.Kind kind, String hash) {
        if (hash == null || hash.isBlank()) return false;
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        return repository.existsByKindAndHashAndActiveTrue(kind, normalized);
    }

    /** 在册摘要列表（active 优先且按登记时间倒序）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KnownGoodHash h : repository.findByActiveTrueOrderByCreatedAtDesc()) {
            items.add(viewOf(h));
        }
        return items;
    }

    /**
     * 登记一条已知good摘要。
     *
     * @param label    人类可读标签（如 "ptv-core v5.4.0 code segment"）
     * @param kind     摘要种类字符串，未知值退到 CODE_SEGMENT
     * @param hash     64 位十六进制摘要（大小写不敏感，入库统一小写）
     * @param operator 操作者（审计）
     * @param active   是否立即生效
     * @return 新登记的行；若已有同 kind/hash 的 active 行则返回该既有行
     */
    public KnownGoodHash add(String label, String kind, String hash, String operator, boolean active) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label 不能为空");
        }
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash 不能为空");
        }
        String normalized = hash.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(normalized).matches()) {
            throw new IllegalArgumentException("hash 必须是 64 位十六进制 SHA-256");
        }
        KnownGoodHash.Kind k = parseKind(kind);
        if (active) {
            for (KnownGoodHash existing : repository.findByActiveTrueOrderByCreatedAtDesc()) {
                if (existing.getKind() == k && normalized.equals(existing.getHash())) {
                    log.info("已知good摘要已存在，返回既有行 kind={} hash={}", k, normalized);
                    return existing;
                }
            }
        }
        KnownGoodHash row = KnownGoodHash.builder()
                .id(UUID.randomUUID().toString())
                .label(label.trim())
                .kind(k)
                .hash(normalized)
                .active(active)
                .createdBy(operator == null ? "" : operator)
                .createdAt(Instant.now())
                .build();
        repository.save(row);
        log.info("登记已知good摘要 kind={} hash={} active={} 操作者={}", k, normalized, active, operator);
        return row;
    }

    /** 下线一条摘要（保留行做审计）。不存在时抛 {@link NoSuchElementException} 供控制层返回 404。 */
    public void deactivate(String id, String operator) {
        if (id == null || id.isBlank()) {
            throw new NoSuchElementException("摘要记录不存在");
        }
        KnownGoodHash row = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("摘要记录不存在: " + id));
        row.setActive(false);
        repository.save(row);
        log.info("下线已知good摘要 id={} hash={} 操作者={}", id, row.getHash(), operator);
    }

    /** 归一摘要种类；未知/空退到 CODE_SEGMENT。 */
    public static KnownGoodHash.Kind parseKind(String raw) {
        if (raw == null) return KnownGoodHash.Kind.CODE_SEGMENT;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (KnownGoodHash.Kind k : KnownGoodHash.Kind.values()) {
            if (k.name().equals(v)) return k;
        }
        return KnownGoodHash.Kind.CODE_SEGMENT;
    }

    /** 行 → 控制层视图。 */
    public Map<String, Object> viewOf(KnownGoodHash h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("label", h.getLabel());
        m.put("kind", h.getKind() == null ? "" : h.getKind().name());
        m.put("hash", h.getHash());
        m.put("active", h.isActive());
        m.put("created_by", h.getCreatedBy());
        m.put("created_at", h.getCreatedAt() == null ? "" : h.getCreatedAt().toString());
        return m;
    }
}