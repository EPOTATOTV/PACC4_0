package com.potatotv.pcu;

import java.net.URI;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * PCU 运行配置。默认值按设计文档取值：检查更新 3 次重试、指数退避、
 * 健康检查 30 秒超时、保留最近 2 个版本的备份。
 */
public final class PcuConfig {

    /** 更新接口默认基址（设计文档 §4.2.1：统一从 pacc.potatotv.asia 下载更新）。 */
    public static final String DEFAULT_BASE_URL = "https://api.potatotv.asia";

    /** §4.2.1 指定的制品下载域，白名单里必须有它。 */
    public static final String DOWNLOAD_HOST = "pacc.potatotv.asia";

    /** 部署里的下载站域：网关配置里 {@code dl.potatotv.asia} 就是放制品的那一台。 */
    public static final String DOWNLOAD_SITE_HOST = "dl.potatotv.asia";

    private final String baseUrl;
    private final UpdatePlatform platform;
    private final String currentVersion;
    private final UpdateChannel channel;
    private final String pteid;
    private final PlatformAdapter adapter;
    private final PublicKey signaturePublicKey;
    private final Path backupRoot;
    private final Path mainArtifact;
    private final boolean allowDelta;
    private final boolean silentUpdate;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration healthCheckTimeout;
    private final Duration healthCheckInterval;
    private final HealthProbe healthProbe;
    private final int keepBackups;
    private final String userAgent;
    private final Set<String> downloadHosts;

    private PcuConfig(Builder b) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(b.baseUrl, "baseUrl"));
        this.platform = b.platform == null ? UpdatePlatform.current() : b.platform;
        this.currentVersion = Objects.requireNonNull(b.currentVersion, "currentVersion");
        this.channel = b.channel == null ? UpdateChannel.STABLE : b.channel;
        this.pteid = b.pteid;
        this.adapter = Objects.requireNonNull(b.adapter, "adapter");
        this.signaturePublicKey = b.signaturePublicKey;
        // 备份默认落在安装目录下的 backup/，与 §4.6.3 的 {pacc_home}/backup/{version}/ 一致。
        // 不能算成安装目录的兄弟目录：安装目录常常只读或指向 Program Files，
        // 兄弟目录不见得可写，回滚时才发现没备份就已经晚了。
        this.backupRoot = b.backupRoot != null ? b.backupRoot
                : this.adapter.getInstallDir().resolve("backup");
        this.mainArtifact = b.mainArtifact == null ? null
                : b.mainArtifact.toAbsolutePath().normalize();
        this.allowDelta = b.allowDelta;
        this.silentUpdate = b.silentUpdate;
        this.maxRetries = Math.max(0, b.maxRetries);
        this.retryBaseDelay = requirePositive(b.retryBaseDelay, "retryBaseDelay");
        this.connectTimeout = requirePositive(b.connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(b.requestTimeout, "requestTimeout");
        this.healthCheckTimeout = requirePositive(b.healthCheckTimeout, "healthCheckTimeout");
        this.healthCheckInterval = requirePositive(b.healthCheckInterval, "healthCheckInterval");
        this.healthProbe = b.healthProbe != null ? b.healthProbe : HealthProbe.assumeHealthy();
        this.keepBackups = Math.max(1, b.keepBackups);
        this.userAgent = b.userAgent != null ? b.userAgent
                : "PACC-PlayerClient/" + SemVer.parse(this.currentVersion) + " (pcu)";
        this.downloadHosts = b.downloadHosts == null || b.downloadHosts.isEmpty()
                ? defaultDownloadHosts(this.baseUrl)
                : normalizeHosts(b.downloadHosts);
        // 启动时就把版本号解析一遍：写错格式要在配置阶段暴露，而不是等到发请求
        SemVer.parse(this.currentVersion);
    }

    /**
     * 默认下载白名单：设计文档 §4.2.1 的 {@code pacc.potatotv.asia}、部署里的下载站
     * {@code dl.potatotv.asia}，外加 baseUrl 的主机（本地联调时更新接口和制品在同一台上，
     * 测试里的假服务也是这个情况）。制品放别处时用 {@code downloadHosts(...)} 覆盖。
     */
    private static Set<String> defaultDownloadHosts(String baseUrl) {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(DOWNLOAD_HOST);
        hosts.add(DOWNLOAD_SITE_HOST);
        String baseHost = hostOf(baseUrl);
        if (baseHost != null) {
            hosts.add(baseHost);
        }
        return Set.copyOf(hosts);
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        Set<String> out = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host != null && !host.isBlank()) {
                out.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (out.isEmpty()) {
            throw new PcuException("下载主机白名单不能全为空");
        }
        return Set.copyOf(out);
    }

    /** 取 URL 的主机名（小写）；解析不出来返回 null，交由后续请求阶段报错。 */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new PcuException(name + " 必须为正数");
        }
        return d;
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public UpdatePlatform platform() {
        return platform;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public SemVer currentSemVer() {
        return SemVer.parse(currentVersion);
    }

    public UpdateChannel channel() {
        return channel;
    }

    public String pteid() {
        return pteid;
    }

    public PlatformAdapter adapter() {
        return adapter;
    }

    public PublicKey signaturePublicKey() {
        return signaturePublicKey;
    }

    public Path backupRoot() {
        return backupRoot;
    }

    /**
     * 非 zip 制品的落地文件（如 {@code ptv-client-5.4.0.jar}）；为 null 时制品按 zip 解包，
     * 逐个覆盖安装目录下的同名相对路径。
     *
     * <p>差分更新只对单文件制品有意义，因此 {@code mainArtifact} 为 null 时不会启用差分。</p>
     */
    public Path mainArtifact() {
        return mainArtifact;
    }

    public boolean allowDelta() {
        return allowDelta;
    }

    public boolean silentUpdate() {
        return silentUpdate;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration retryBaseDelay() {
        return retryBaseDelay;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration healthCheckTimeout() {
        return healthCheckTimeout;
    }

    public Duration healthCheckInterval() {
        return healthCheckInterval;
    }

    public HealthProbe healthProbe() {
        return healthProbe;
    }

    public int keepBackups() {
        return keepBackups;
    }

    public String userAgent() {
        return userAgent;
    }

    /** 允许下载制品的主机（小写、不含端口）。清单里的下载地址只有落在这些主机上才会被请求。 */
    public Set<String> downloadHosts() {
        return downloadHosts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String baseUrl = DEFAULT_BASE_URL;
        private UpdatePlatform platform;
        private String currentVersion;
        private UpdateChannel channel = UpdateChannel.STABLE;
        private String pteid;
        private PlatformAdapter adapter;
        private PublicKey signaturePublicKey;
        private Path backupRoot;
        private Path mainArtifact;
        private boolean allowDelta = true;
        private boolean silentUpdate;
        private int maxRetries = 3;
        private Duration retryBaseDelay = Duration.ofMillis(500);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration healthCheckTimeout = Duration.ofSeconds(30);
        private Duration healthCheckInterval = Duration.ofSeconds(1);
        private HealthProbe healthProbe;
        private int keepBackups = 2;
        private String userAgent;
        private Set<String> downloadHosts;

        public Builder baseUrl(String v) {
            this.baseUrl = v;
            return this;
        }

        public Builder platform(UpdatePlatform v) {
            this.platform = v;
            return this;
        }

        public Builder currentVersion(String v) {
            this.currentVersion = v;
            return this;
        }

        public Builder channel(UpdateChannel v) {
            this.channel = v;
            return this;
        }

        public Builder pteid(String v) {
            this.pteid = v;
            return this;
        }

        public Builder adapter(PlatformAdapter v) {
            this.adapter = v;
            return this;
        }

        public Builder signaturePublicKey(PublicKey v) {
            this.signaturePublicKey = v;
            return this;
        }

        public Builder backupRoot(Path v) {
            this.backupRoot = v;
            return this;
        }

        public Builder mainArtifact(Path v) {
            this.mainArtifact = v;
            return this;
        }

        public Builder allowDelta(boolean v) {
            this.allowDelta = v;
            return this;
        }

        public Builder silentUpdate(boolean v) {
            this.silentUpdate = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public Builder retryBaseDelay(Duration v) {
            this.retryBaseDelay = v;
            return this;
        }

        public Builder connectTimeout(Duration v) {
            this.connectTimeout = v;
            return this;
        }

        public Builder requestTimeout(Duration v) {
            this.requestTimeout = v;
            return this;
        }

        public Builder healthCheckTimeout(Duration v) {
            this.healthCheckTimeout = v;
            return this;
        }

        public Builder healthCheckInterval(Duration v) {
            this.healthCheckInterval = v;
            return this;
        }

        public Builder healthProbe(HealthProbe v) {
            this.healthProbe = v;
            return this;
        }

        public Builder keepBackups(int v) {
            this.keepBackups = v;
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgent = v;
            return this;
        }

        /** 覆盖下载主机白名单；不设时取 {@code pacc.potatotv.asia} 与 baseUrl 的主机。 */
        public Builder downloadHosts(Set<String> v) {
            this.downloadHosts = v;
            return this;
        }

        public PcuConfig build() {
            return new PcuConfig(this);
        }
    }
}