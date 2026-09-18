package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.EffectConfig;
import com.potatotv.pacc.repository.EffectConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 动效配置中心：读写全局动效档位与红屏模板。客户端需校验合法枚举。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffectConfigService {

    private static final String DEFAULT_ID = "default";
    private static final Set<String> LEVELS = Set.of("off", "gentle", "standard", "strong");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final EffectConfigRepository repository;
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
        return repository.save(next);
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