package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.DetectorConfig;
import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.repository.DetectorConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * §4.3.3 动作②：新型作弊 IOC 出现 → 自动生成检测规则并灰度下发。
 * <p>在 {@code t_detector_config} 生成一个默认关闭（灰度）的检测器配置，运营可逐步放量。</p>
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储 null 分析误报
public class GenerateRuleGrayHandler implements AutomationActionHandler {

    private final DetectorConfigRepository detectorConfigRepository;

    @Override
    public String code() {
        return "GENERATE_RULE_GRAY";
    }

    @Override
    public AutomationActionResult execute(AutomationContext context) {
        String family = sanitize(context.metricString("family", "ioc"));
        String key = "auto_ioc_" + family;
        DetectorConfig config = detectorConfigRepository.findByDetectorKey(key).orElse(null);
        boolean created = config == null;
        if (created) {
            config = DetectorConfig.builder()
                    .id("dc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .detectorKey(key)
                    .name("自动生成：IOC " + family)
                    .enabled(false)
                    .metaJson("{\"gray\":true,\"source\":\"automation\"}")
                    .updatedBy(context.actor())
                    .build();
        } else {
            config.setEnabled(false);
            config.setMetaJson("{\"gray\":true,\"source\":\"automation\"}");
            config.setUpdatedBy(context.actor());
            config.setUpdatedAt(Instant.now());
        }
        detectorConfigRepository.save(config);
        return AutomationActionResult.applied("detectorKey=" + key + ";gray=true;created=" + created);
    }

    @Override
    public AutomationActionResult revert(AutomationExecution execution) {
        String key = AutomationDetails.token(execution.getDetail(), "detectorKey");
        if (key == null) {
            return AutomationActionResult.noop("无法解析 detectorKey");
        }
        DetectorConfig config = detectorConfigRepository.findByDetectorKey(key).orElse(null);
        if (config == null) {
            return AutomationActionResult.noop("检测器已不存在: " + key);
        }
        config.setEnabled(false);
        config.setMetaJson("{\"gray\":false,\"reverted\":true}");
        config.setUpdatedAt(Instant.now());
        detectorConfigRepository.save(config);
        return AutomationActionResult.applied("detectorKey=" + key + " 已撤销灰度");
    }

    private static String sanitize(String s) {
        String v = s == null ? "ioc" : s.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return v.isEmpty() ? "ioc" : v;
    }
}