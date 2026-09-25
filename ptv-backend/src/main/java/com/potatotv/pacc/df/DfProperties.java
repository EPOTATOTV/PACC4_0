package com.potatotv.pacc.df;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF Alpha 1.0.0（kernel 5.4.0）运行时配置。
 *
 * <p>全部字段带安全默认值，因此无需改动 {@code application.yml}；
 * 如需覆盖，在部署环境注入 {@code PACC_DF_*} 环境变量或同名配置项即可。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pacc.df")
public class DfProperties {

    private Plugin plugin = new Plugin();
    private Tenant tenant = new Tenant();
    private Alert alert = new Alert();
    private Automation automation = new Automation();

    /** §4.2.2 插件沙箱限制。 */
    @Data
    public static class Plugin {
        /** 单次 detect 的超时（毫秒）。 */
        private long timeoutMs = 200;
        /** 单个插件的时间窗口内 CPU 预算（毫秒），超预算即隔离。 */
        private long maxCpuMsPerWindow = 5_000;
        /** 插件输出证据的字节上限，超限截断。 */
        private int maxOutputBytes = 16_384;
        /** 沙箱 API 白名单：插件声明的 api 超出该集合即拒绝执行。 */
        private List<String> allowedApis = List.of("feature:read", "result:emit", "metric:read", "log");
        /** 热加载时扫描 classes 的最大数量（目录型插件）。 */
        private int maxScanClasses = 512;
        /**
         * 插件包目录：热加载请求里的 path 一律按「相对本目录」解析，越界即拒绝。
         * 这是插件加载唯一被允许的取件范围，改大它等于放宽沙箱的入口面。
         */
        private String pluginDir = "plugins";
    }

    /** §4.2.3 多租户隔离与配额。 */
    @Data
    public static class Tenant {
        /** 平台级角色（可跨租户、可切换租户上下文）。 */
        private List<String> platformRoles = List.of("super-admin", "operator", "api-key");
        /** 是否信任 X-Tenant-Id 头（仅对平台级身份生效；租户级身份一律以绑定关系校验）。 */
        private boolean allowHeaderSwitch = true;
    }

    /** §4.3.2 告警降噪。 */
    @Data
    public static class Alert {
        /** 聚合窗口（分钟）：同玩家/同家族在此窗口内合并为一条。 */
        private int aggregationWindowMinutes = 60;
        /** 低优先级延迟批量通知的延迟（分钟）。 */
        private int lowPriorityDelayMinutes = 15;
    }

    /** §4.3.3 自动化响应。 */
    @Data
    public static class Automation {
        /** 评估周期（毫秒）。 */
        private long evaluateIntervalMs = 60_000;
        /** 全局开关：关闭后任何规则都不执行（no-op）。 */
        private boolean enabled = true;
        /** 单条规则两次执行的最小间隔（分钟）。 */
        private int defaultCooldownMinutes = 30;
    }
}