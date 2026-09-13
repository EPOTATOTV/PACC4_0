package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DetectorConfig;
import com.potatotv.pacc.repository.DetectorConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检测器配置服务：启停开关 + 阈值/参数（JSON）的只读派发与运营写入。
 * 检测管线按 detectorKey 查询启停状态；默认启用（没有任何配置时可放行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectorConfigService {

    private final DetectorConfigRepository detectorConfigRepository;

    /** 内置检测器清单（未配置时给前端展示的可调项）。 */
    public static final String[][] DETECTORS = {
            {"dma", "DMA 外设检测"}, {"io", "IO 外设检测"},
            {"memory", "内存篡改检测"}, {"proc", "进程注入检测"},
            {"signature", "特征库匹配"}, {"ai", "AI 行为评分"},
    };

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DetectorConfig c : detectorConfigRepository.findAllByOrderByDetectorKeyAsc()) {
            out.add(row(c));
        }
        return out;
    }

    /** 合并内置清单，保证未配置的检测器也出现在目录中（默认启用）。 */
    public List<Map<String, Object>> catalog() {
        Map<String, DetectorConfig> byKey = new LinkedHashMap<>();
        for (DetectorConfig c : detectorConfigRepository.findAllByOrderByDetectorKeyAsc()) {
            byKey.put(c.getDetectorKey(), c);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] d : DETECTORS) {
            DetectorConfig c = byKey.get(d[0]);
            if (c != null) {
                out.add(row(c));
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("detector_key", d[0]);
                m.put("name", d[1]);
                m.put("enabled", true);
                m.put("meta", "");
                m.put("configured", false);
                out.add(m);
            }
        }
        return out;
    }

    /** 某检测器是否启用（无配置默认开启）。 */
    public boolean isEnabled(String detectorKey) {
        if (detectorKey == null || detectorKey.isBlank()) {
            return true;
        }
        return detectorConfigRepository.findByDetectorKey(detectorKey)
                .map(DetectorConfig::isEnabled)
                .orElse(true);
    }

    /** 新增或更新检测器配置（upsert by key）。 */
    @Transactional
    public Map<String, Object> upsert(String detectorKey, String name, boolean enabled,
                                      String metaJson, String operator) {
        String key = detectorKey == null || detectorKey.isBlank()
                ? throwBad("检测器键不能为空") : detectorKey.trim();
        DetectorConfig c = detectorConfigRepository.findByDetectorKey(key).orElse(null);
        if (c == null) {
            c = DetectorConfig.builder()
                    .id(UUID.randomUUID().toString())
                    .detectorKey(key)
                    .name(name == null || name.isBlank() ? key : name)
                    .enabled(enabled)
                    .metaJson(metaJson)
                    .updatedBy(operator)
                    .updatedAt(java.time.Instant.now())
                    .build();
        } else {
            if (name != null && !name.isBlank()) c.setName(name);
            c.setEnabled(enabled);
            c.setMetaJson(metaJson);
            c.setUpdatedBy(operator);
            c.setUpdatedAt(java.time.Instant.now());
        }
        detectorConfigRepository.save(c);
        log.info("检测器配置写入 key={} enabled={} by={}", key, enabled, operator);
        return row(c);
    }

    private Map<String, Object> row(DetectorConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detector_key", c.getDetectorKey());
        m.put("name", c.getName());
        m.put("enabled", c.isEnabled());
        m.put("meta", c.getMetaJson() == null ? "" : c.getMetaJson());
        m.put("updated_by", c.getUpdatedBy());
        m.put("updated_at", c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString());
        m.put("configured", true);
        return m;
    }

    private String throwBad(String msg) {
        throw new IllegalArgumentException(msg);
    }
}