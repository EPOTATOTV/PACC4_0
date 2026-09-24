package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.EffectConfig;
import com.potatotv.pacc.domain.EffectConfigAudit;
import com.potatotv.pacc.repository.EffectConfigAuditRepository;
import com.potatotv.pacc.repository.EffectConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 动效配置中心：读写全局动效档位与红屏模板。客户端需校验合法枚举。
 * 每次保存落一条审计（谁改的 + 改了什么），供管理端「变更历史」回溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffectConfigService {

    private static final String DEFAULT_ID = "default";
    private static final Set<String> LEVELS = Set.of("off", "gentle", "standard", "strong");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    /** summary 列长 512，留出余量后截断，避免长 diff 直接写库失败。 */
    private static final int SUMMARY_MAX = 480;

    private final EffectConfigRepository repository;
    private final EffectConfigAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public EffectConfig current() {
        return repository.findById(DEFAULT_ID)
                .orElseGet(() -> EffectConfig.builder().id(DEFAULT_ID).build());
    }

    /** 校验并更新。effects 与 redscreen 两个 JSON 可选，缺省时合并保留旧值。 */
    @Transactional
    public EffectConfig update(Map<String, Object> body) {
        String level = body.get("motion_level") == null ? null : body.get("motion_level").toString().toLowerCase();
        String template = body.get("redscreen_template") == null ? null : body.get("redscreen_template").toString().toLowerCase();
        if (level != null && !LEVELS.contains(level)) {
            throw new IllegalArgumentException("motion_level 非法：" + level);
        }
        if (template != null && !LEVELS.contains(template)) {
            throw new IllegalArgumentException("redscreen_template 非法：" + template);
        }

        EffectConfig cur = current();
        EffectConfig next = EffectConfig.builder()
                .id(DEFAULT_ID)
                .motionLevel(level == null ? cur.getMotionLevel() : level)
                .effectsJson(mergeJson(cur.getEffectsJson(), body.get("effects_json")))
                .redscreenTemplate(template == null ? cur.getRedscreenTemplate() : template)
                .redscreenJson(mergeJson(cur.getRedscreenJson(), body.get("redscreen_json")))
                .updatedBy(body.get("updated_by") == null ? null : body.get("updated_by").toString())
                .updatedAt(Instant.now())
                .build();
        EffectConfig saved = repository.save(next);
        auditRepository.save(EffectConfigAudit.builder()
                .changedBy(saved.getUpdatedBy() == null || saved.getUpdatedBy().isBlank() ? "unknown" : saved.getUpdatedBy())
                .motionLevel(saved.getMotionLevel())
                .redscreenTemplate(saved.getRedscreenTemplate())
                .summary(diff(cur, saved))
                .effectsJson(saved.getEffectsJson())
                .redscreenJson(saved.getRedscreenJson())
                .createdAt(saved.getUpdatedAt())
                .build());
        return saved;
    }

    /** 最近 N 条变更记录（倒序），供管理端「变更历史」展示。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (EffectConfigAudit a : auditRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("changedBy", a.getChangedBy());
            m.put("motionLevel", a.getMotionLevel());
            m.put("redscreenTemplate", a.getRedscreenTemplate());
            m.put("summary", a.getSummary());
            m.put("createdAt", a.getCreatedAt().toEpochMilli());
            out.add(m);
        }
        return out;
    }

    /** 变更摘要：档位 / 红屏模板 / 各开关逐项对比，只列真正变了的部分。 */
    private String diff(EffectConfig before, EffectConfig after) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.getMotionLevel(), after.getMotionLevel())) {
            parts.add("档位 " + before.getMotionLevel() + "→" + after.getMotionLevel());
        }
        if (!Objects.equals(before.getRedscreenTemplate(), after.getRedscreenTemplate())) {
            parts.add("红屏模板 " + before.getRedscreenTemplate() + "→" + after.getRedscreenTemplate());
        }
        // 键取并集并排序，保证同一组改动每次生成的摘要一致
        Set<String> keys = new TreeSet<>(readJson(before.getEffectsJson()).keySet());
        keys.addAll(readJson(after.getEffectsJson()).keySet());
        Map<String, Object> b = readJson(before.getEffectsJson());
        Map<String, Object> a = readJson(after.getEffectsJson());
        for (String k : keys) {
            if (!Objects.equals(b.get(k), a.get(k))) {
                parts.add("动效 " + k + " " + brief(b.get(k)) + "→" + brief(a.get(k)));
            }
        }

        Set<String> rKeys = new TreeSet<>(readJson(before.getRedscreenJson()).keySet());
        rKeys.addAll(readJson(after.getRedscreenJson()).keySet());
        Map<String, Object> rb = readJson(before.getRedscreenJson());
        Map<String, Object> ra = readJson(after.getRedscreenJson());
        for (String k : rKeys) {
            if (!Objects.equals(rb.get(k), ra.get(k))) {
                parts.add("红屏 " + k + " " + brief(rb.get(k)) + "→" + brief(ra.get(k)));
            }
        }

        if (parts.isEmpty()) {
            return "无实质变更（重复保存）";
        }
        String s = String.join("；", parts);
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX - 3) + "..." : s;
    }

    /** 摘要里的值：布尔说开/关，空值说未设，其余原样。 */
    private static String brief(Object v) {
        if (v == null) {
            return "未设";
        }
        if (v instanceof Boolean bool) {
            return bool ? "开" : "关";
        }
        return String.valueOf(v);
    }

    /** 客户端下发视图：档位 + 动效开关 + 红屏模板参数。 */
    @Transactional(readOnly = true)
    public Map<String, Object> clientView() {
        EffectConfig c = current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("motion_level", c.getMotionLevel());
        out.put("effects", readJson(c.getEffectsJson()));
        out.put("redscreen_template", c.getRedscreenTemplate());
        out.put("redscreen", readJson(c.getRedscreenJson()));
        out.put("updated_at", c.getUpdatedAt().toEpochMilli());
        return out;
    }

    private String mergeJson(String base, Object incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(readJson(base));
        if (incoming instanceof String s) {
            merged.putAll(readJson(s));
        } else if (incoming instanceof Map<?, ?> m) {
            m.forEach((k, v) -> merged.put(String.valueOf(k), v));
        }
        return write(merged);
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception e) {
            log.warn("effect config json 解析失败，回退为空：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String write(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}