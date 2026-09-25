package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationState;
import com.potatotv.pacc.repository.AutomationStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §4.3.3 自动化响应状态读写：把自动动作的结果落成「其他服务可读的真实开关」，
 * 而非仅打日志。
 *
 * <p>例如 {@link #isNonCriticalDetectionEnabled()} 与 {@link #isDegradedMode()} 由检测/上报服务读取，
 * 直接决定是否关闭非关键检测、是否降低上报频率。</p>
 */
@Service
@RequiredArgsConstructor
public class AutomationStateService {

    /** 降级模式开关。 */
    public static final String KEY_DEGRADED_MODE = "degraded_mode";
    /** 检测灵敏度倍数。 */
    public static final String KEY_SENSITIVITY = "detection_sensitivity";
    /** 上报频率倍数（>1 表示降低上报频率）。 */
    public static final String KEY_REPORT_INTERVAL = "report_interval_multiplier";
    /** 非关键检测开关。 */
    public static final String KEY_NON_CRITICAL_DETECTION = "non_critical_detection_enabled";
    /** 灰度暂停开关。 */
    public static final String KEY_CANARY_PAUSED = "canary_paused";
    /** 当前生效客户端版本。 */
    public static final String KEY_ACTIVE_VERSION = "active_client_version";
    /** 回滚目标版本。 */
    public static final String KEY_ROLLBACK_VERSION = "rollback_version";

    private final AutomationStateRepository repository;

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return repository.findById(key).map(AutomationState::getStateValue).orElse(defaultValue);
    }

    @Transactional
    public void put(String key, String value, String by) {
        AutomationState state = repository.findById(key).orElseGet(() -> AutomationState.builder().stateKey(key).build());
        state.setStateValue(value);
        state.setUpdatedAt(Instant.now());
        state.setUpdatedBy(by);
        repository.save(state);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public void setBool(String key, boolean value, String by) {
        put(key, Boolean.toString(value), by);
    }

    public double getNumber(String key, double defaultValue) {
        String v = get(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void setNumber(String key, double value, String by) {
        put(key, Double.toString(value), by);
    }

    /* ---------------- 其他服务直接消费的开关 ---------------- */

    /** 是否处于降级模式（后端负载过高时自动开启）。 */
    public boolean isDegradedMode() {
        return getBool(KEY_DEGRADED_MODE, false);
    }

    /** 是否允许非关键检测（负载过高时自动关闭）。 */
    public boolean isNonCriticalDetectionEnabled() {
        return getBool(KEY_NON_CRITICAL_DETECTION, true);
    }

    /** 检测灵敏度倍数（同一作弊家族检测风暴时自动升级）。 */
    public double detectionSensitivity() {
        return getNumber(KEY_SENSITIVITY, 1.0);
    }

    /** 上报频率倍数（>1 = 降低上报频率）。 */
    public double reportIntervalMultiplier() {
        return getNumber(KEY_REPORT_INTERVAL, 1.0);
    }

    /** 是否已暂停灰度。 */
    public boolean isCanaryPaused() {
        return getBool(KEY_CANARY_PAUSED, false);
    }

    /** 全量状态快照（管理端展示用）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (AutomationState s : repository.findAll()) {
            m.put(s.getStateKey(), s.getStateValue());
        }
        return m;
    }
}