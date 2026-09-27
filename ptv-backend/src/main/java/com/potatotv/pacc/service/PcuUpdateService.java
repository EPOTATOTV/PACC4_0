package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ReleaseInfo;
import com.potatotv.pacc.domain.UpdateReport;
import com.potatotv.pacc.repository.UpdateReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PCU（跨平台更新模块）服务端：按 platform×channel 下发灰度可及的最新版本、对差分包做精确基线匹配，
 * 并接收端侧更新结果上报（设计文档 §4.11 / §5.3）。
 *
 * <p>面向前端/端侧的是无登录态公共接口，因此对所有入参都不抛异常：非法 platform/channel 归一到
 * windows/stable，非法或缺失的 current_version 按 0.0.0 处理（任何已发布版本都视为可更新）。</p>
 *
 * <p><b>灰度选择。</b>从最新已发布版本往下走，取第一个「比当前新」且「这台设备够资格」的版本。
 * 不够资格的设备会拿到上一个已放量的版本，而不是被告诉「没有更新」—— 后者会让设备停在更旧的
 * 版本上，等于灰度把老版本也一起冻住了。没有任何版本够资格时才返回 has_update=false。</p>
 */
@Slf4j
@Service
public class PcuUpdateService {

    /** 上报状态白名单；其余一律归一为 failed。 */
    private static final Set<String> REPORT_STATUSES = Set.of("success", "failed", "rolled_back", "skipped");

    /** 计入失败率的终态：更新失败与回滚都说明这一版本在这台设备上没跑起来。 */
    private static final Set<String> FAILURE_STATUSES = Set.of("failed", "rolled_back");

    /** 分桶基数。灰度比例就是「100 个桶里放几个」。 */
    private static final int BUCKETS = 100;

    // 列宽上限与 V37 迁移脚本里的 t_update_report 定义一一对应。这是无登录态的公共接口，
    // 入参全部来自端侧，超出列宽必须在这里截断：留给数据库抛异常等于用一个脏上报换回一个 500。
    private static final int MAX_PTEID_LENGTH = 64;
    private static final int MAX_PLATFORM_LENGTH = 16;
    private static final int MAX_VERSION_LENGTH = 48;
    private static final int MAX_ERROR_LENGTH = 500;

    private final ReleaseService releaseService;
    private final UpdateReportRepository updateReportRepository;

    /** 内部测试设备（PTEID，逗号分隔）。灰度的第一环，任何比例下都优先放行。 */
    private final Set<String> internalPteids;

    /** 失败率上限（%）。超过就把该版本的灰度视为已暂停，不再下发。 */
    private final int failureRateLimitPct;

    /** 失败率判定的最小样本数。样本太少时比例没有意义，宁可先放量再观察。 */
    private final int failureRateMinSamples;

    /** 失败率统计窗口。 */
    private final Duration failureRateWindow;

    public PcuUpdateService(ReleaseService releaseService,
                            UpdateReportRepository updateReportRepository,
                            @Value("${pacc.update.internal-pteids:}") String internalPteids,
                            @Value("${pacc.update.failure-rate-limit-pct:5}") int failureRateLimitPct,
                            @Value("${pacc.update.failure-rate-min-samples:20}") int failureRateMinSamples,
                            @Value("${pacc.update.failure-rate-window-hours:24}") long failureRateWindowHours) {
        this.releaseService = releaseService;
        this.updateReportRepository = updateReportRepository;
        this.internalPteids = parsePteids(internalPteids);
        this.failureRateLimitPct = failureRateLimitPct;
        this.failureRateMinSamples = Math.max(1, failureRateMinSamples);
        this.failureRateWindow = Duration.ofHours(Math.max(1, failureRateWindowHours));
    }

    /** 检查更新：has_update=true 时返回端侧解析器写死的键名集合。 */
    public Map<String, Object> check(String platform, String currentVersion, String channel, String pteid) {
        String pf = norm(platform, ReleaseService.PLATFORMS, "windows");
        String ch = norm(channel, ReleaseService.CHANNELS, "stable");
        String current = currentVersion == null || currentVersion.isBlank() ? "0.0.0" : currentVersion.trim();

        Optional<ReleaseInfo> target = pickTarget(pf, ch, current, pteid);
        if (target.isEmpty()) {
            return noUpdate(pf, current);
        }
        ReleaseInfo r = target.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("has_update", true);
        m.put("platform", pf);
        m.put("latest_version", r.getVersion());
        m.put("download_url", r.getDownloadUrl());
        m.put("checksum", withShaPrefix(r.getSha256()));
        m.put("size", r.getFileSize());
        m.put("force_update", r.isForcedEnabled());
        m.put("changelog", r.getNotes());
        m.put("min_app_version", r.getMinAppVersion());
        m.put("signature", r.getSignature());
        Map<String, Object> delta = deltaOf(r, current);
        if (delta != null) {
            m.put("delta", delta);
        }
        log.debug("更新检查 platform={} channel={} current={} latest={} pteid={} rollout={}",
                pf, ch, current, r.getVersion(), pteid, r.getRolloutPercent());
        return m;
    }

    // ------------------------------------------------------------------ 灰度选择

    /** 从最新往下取第一个「比当前新」且本设备够资格的版本。 */
    private Optional<ReleaseInfo> pickTarget(String platform, String channel, String current, String pteid) {
        for (ReleaseInfo release : releaseService.publishedNewestFirst(platform, channel)) {
            if (ReleaseService.compareVersions(release.getVersion(), current) <= 0) {
                // 已按版本降序排列，遇到不比自己新的版本，后面的只会更旧
                break;
            }
            if (eligible(release, pteid)) {
                return Optional.of(release);
            }
        }
        return Optional.empty();
    }

    /** 这台设备是否够资格拿到该版本。 */
    private boolean eligible(ReleaseInfo release, String pteid) {
        if (release.getRolloutPercent() >= 100) {
            return true;
        }
        if (release.getRolloutPercent() <= 0) {
            return false;
        }
        if (isInternal(pteid)) {
            return true;
        }
        if (rolloutPaused(release)) {
            return false;
        }
        // 没有 PTEID 就分不了桶。这种情况保守处理：留在当前版本，等放量到 100% 再升。
        if (pteid == null || pteid.isBlank()) {
            return false;
        }
        return bucketOf(pteid) < release.getRolloutPercent();
    }

    private boolean isInternal(String pteid) {
        return pteid != null && internalPteids.contains(pteid.trim());
    }

    /**
     * 失败率是否已超线（设计文档 §5.3「更新失败率 > 5% → 自动回滚」）。
     *
     * <p>这里做的是「按读取暂停」而不是定时任务改写发布状态：改写状态会丢掉「这版本本来是灰度中」
     * 这件事，而且要额外一套恢复逻辑。读取时判定则天然可自愈 —— 失败率降下来，灰度自动继续。</p>
     */
    private boolean rolloutPaused(ReleaseInfo release) {
        Instant since = Instant.now().minus(failureRateWindow);
        long total = updateReportRepository.countByToVersionAndCreatedAtAfter(release.getVersion(), since);
        if (total < failureRateMinSamples) {
            return false;
        }
        long failed = updateReportRepository
                .countByToVersionAndStatusInAndCreatedAtAfter(release.getVersion(), FAILURE_STATUSES, since);
        boolean paused = failed * 100L > (long) failureRateLimitPct * total;
        if (paused) {
            log.warn("灰度暂停：版本 {} 近 {} 小时失败率 {}% 超过上限 {}%（样本 {}）",
                    release.getVersion(), failureRateWindow.toHours(),
                    Math.round(failed * 100.0 / total), failureRateLimitPct, total);
        }
        return paused;
    }

    /**
     * 把 PTEID 映射到 [0,100) 的桶。
     *
     * <p>用 SHA-256 而不是 {@code String.hashCode()}：后者在相邻 ID 上分布很差，同一批玩家会被
     * 成片分到同一个桶里，1% 灰度可能实际只覆盖到某一类 ID。哈希只取 PTEID，保证同一台设备
     * 在不同版本上的桶位一致，便于按人群对比灰度效果。</p>
     *
     * <p>规则下发（{@code /api/player/rules/manifest}）复用同一个方法：两套灰度若用不同的分桶，
     * 同一台设备在「客户端更新」和「规则灰度」里落在不同人群，灰度对比就没法互相印证。</p>
     */
    public static int bucketOf(String pteid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pteid.trim().getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return Math.floorMod(value, BUCKETS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境本身坏了
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Set<String> parsePteids(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return Set.copyOf(out);
    }

    /** 落库更新结果上报。状态归一，错误信息截断；不落 pteid 以外的用户隐私。 */
    @Transactional
    public void report(String pteid, String platform, String fromVersion, String toVersion,
                       String status, String errorMessage) {
        String st = status == null ? "" : status.trim().toLowerCase();
        if (!REPORT_STATUSES.contains(st)) {
            log.warn("更新上报状态非法（归一为 failed）：{}", status);
            st = "failed";
        }
        UpdateReport report = UpdateReport.builder()
                .id(UUID.randomUUID().toString())
                .pteid(column(pteid, MAX_PTEID_LENGTH))
                .platform(column(platform, MAX_PLATFORM_LENGTH))
                .fromVersion(column(fromVersion, MAX_VERSION_LENGTH))
                .toVersion(column(toVersion, MAX_VERSION_LENGTH))
                .status(st)
                .errorMessage(column(errorMessage, MAX_ERROR_LENGTH))
                .createdAt(Instant.now())
                .build();
        updateReportRepository.save(report);
        log.info("更新结果上报 platform={} {}->{} status={} pteid={}",
                report.getPlatform(), report.getFromVersion(), report.getToVersion(), st, report.getPteid());
    }

    /**
     * delta 只在 delta_url 非空且 delta_from_version 与端侧 current_version 精确相等时下发；
     * 版本不等或字段缺失都不带 delta 键，端侧据此回退全量下载。
     */
    private Map<String, Object> deltaOf(ReleaseInfo r, String currentVersion) {
        String from = r.getDeltaFromVersion() == null ? null : r.getDeltaFromVersion().trim();
        if (r.getDeltaUrl() == null || r.getDeltaUrl().isBlank() || !currentVersion.equals(from)) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("from_version", from);
        d.put("url", r.getDeltaUrl());
        d.put("checksum", withShaPrefix(r.getDeltaSha256()));
        d.put("size", r.getDeltaSize());
        return d;
    }

    private Map<String, Object> noUpdate(String platform, String currentVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("has_update", false);
        m.put("latest_version", currentVersion);
        m.put("platform", platform);
        return m;
    }

    /** 库里存的是不带前缀的十六进制，输出时统一补 sha256: 前缀，端侧按前缀识别算法。 */
    private static String withShaPrefix(String hex) {
        if (hex == null || hex.isBlank()) return hex;
        String h = hex.trim();
        return h.regionMatches(true, 0, "sha256:", 0, 7) ? h : "sha256:" + h;
    }

    /** 去空白后按列宽截断；空串归一为 null，避免往库里写空字符串。 */
    private static String column(String text, int maxLength) {
        String flat = blankToNull(text);
        if (flat == null) return null;
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String norm(String v, List<String> allowed, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        String low = v.trim().toLowerCase();
        return allowed.contains(low) ? low : fallback;
    }
}