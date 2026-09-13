package com.potatotv.pacc.controller;

import com.potatotv.pacc.repository.AdminLoginLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统状态与管理配置只读接口。受默认 /api/admin/** 认证保护。
 *
 * <p>仅暴露布尔开关、阈值等非敏感信息；密钥类配置一律不返回明文。</p>
 */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class SystemController {

    private static final String APP_VERSION = "5.0.0";

    private final AdminLoginLogRepository loginLogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    @Value("${pacc.detection.redscreen-threshold:85}")
    private int redscreenThreshold;
    @Value("${pacc.detection.severe-threshold:95}")
    private int severeThreshold;
    @Value("${pacc.detection.suspicious-low:70}")
    private int suspiciousLow;
    @Value("${pacc.detection.cooldown-minutes:10}")
    private int cooldownMinutes;
    @Value("${pacc.rules.max-bonus:15}")
    private int rulesMaxBonus;
    @Value("${pacc.feishu.enabled:false}")
    private boolean feishuEnabled;
    @Value("${pacc.ai.enabled:false}")
    private boolean aiEnabled;
    @Value("${pacc.countermeasure.input-macro-cv:0.06}")
    private double inputMacroCv;
    @Value("${pacc.countermeasure.integrity-suspect:25}")
    private int integritySuspect;
    @Value("${pacc.countermeasure.integrity-tampered:60}")
    private int integrityTampered;
    @Value("${pacc.rules.enabled:true}")
    private boolean rulesEnabled;
    @Value("${pacc.security.super-admin-key:}")
    private String superAdminKey;
    @Value("${pacc.security.admin-api-key:}")
    private String operatorKey;

    /** 应用版本：优先读 jar manifest，未打包运行时回退到内置常量。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        String manifestVersion = SystemController.class.getPackage() == null
                ? null : SystemController.class.getPackage().getImplementationVersion();
        String db = "unknown";
        try {
            db = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        } catch (Exception ignored) {
            // 数据库暂不可用时不阻塞系统信息返回
        }
        return Map.of(
                "app", environment.getProperty("spring.application.name", "pacc-ptv-backend"),
                "version", manifestVersion == null ? APP_VERSION : manifestVersion,
                "java_version", System.getProperty("java.version"),
                "active_profiles", environment.getActiveProfiles(),
                "uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime(),
                "database", db == null ? "unknown" : db,
                "host_uptime", "ok");
    }

    /** 检测策略等配置快照（只读，供管理端查看当前姿态）。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detection", Map.of(
                "redscreen_threshold", redscreenThreshold,
                "severe_threshold", severeThreshold,
                "suspicious_low", suspiciousLow,
                "cooldown_minutes", cooldownMinutes));
        m.put("countermeasure", Map.of(
                "input_macro_cv", inputMacroCv,
                "integrity_suspect", integritySuspect,
                "integrity_tampered", integrityTampered));
        m.put("rules", Map.of(
                "enabled", rulesEnabled,
                "max_bonus", rulesMaxBonus));
        m.put("ai", Map.of("enabled", aiEnabled));
        m.put("feishu", Map.of("enabled", feishuEnabled));
        return m;
    }

    /** 管理员身份概览：密钥启用情况 + 飞书白名单账号数，均为只读。 */
    @GetMapping("/admins")
    public Map<String, Object> admins() {
        List<Map<String, Object>> accounts = new ArrayList<>();
        if (superAdminKey != null && !superAdminKey.isBlank()) {
            accounts.add(Map.of("identity", "超级管理员密钥", "method", "key", "role", "super-admin", "enabled", true));
        }
        if (operatorKey != null && !operatorKey.isBlank()) {
            accounts.add(Map.of("identity", "运维管理员密钥", "method", "key", "role", "operator", "enabled", true));
        }
        if (feishuEnabled) {
            accounts.add(Map.of("identity", "飞书 SSO 白名单", "method", "feishu", "role", "super-admin/operator",
                    "enabled", true));
        }
        if (accounts.isEmpty()) {
            accounts.add(Map.of("identity", "未配置管理员", "method", "none", "role", "-", "enabled", false));
        }

        long recentLogins = loginLogRepository.count();
        return Map.of("accounts", accounts, "recent_login_events", recentLogins,
                "note", "管理员由配置文件/环境变量固化，本页只读；调整需由运维在部署侧修改后重启");
    }
}