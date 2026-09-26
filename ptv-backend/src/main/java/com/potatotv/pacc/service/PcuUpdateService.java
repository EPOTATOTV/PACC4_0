package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ReleaseInfo;
import com.potatotv.pacc.domain.UpdateReport;
import com.potatotv.pacc.repository.UpdateReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PCU（跨平台更新模块）服务端：按 platform×channel 下发最新已发布版本、对差分包做精确基线匹配，
 * 并接收端侧更新结果上报（设计文档 §4.11）。
 *
 * <p>面向前端/端侧的是无登录态公共接口，因此对所有入参都不抛异常：非法 platform/channel 归一到
 * windows/stable，非法或缺失的 current_version 按 0.0.0 处理（任何已发布版本都视为可更新）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PcuUpdateService {

    /** 上报状态白名单；其余一律归一为 failed。 */
    private static final Set<String> REPORT_STATUSES = Set.of("success", "failed", "rolled_back", "skipped");

    // 列宽上限与 V37 迁移脚本里的 t_update_report 定义一一对应。这是无登录态的公共接口，
    // 入参全部来自端侧，超出列宽必须在这里截断：留给数据库抛异常等于用一个脏上报换回一个 500。
    private static final int MAX_PTEID_LENGTH = 64;
    private static final int MAX_PLATFORM_LENGTH = 16;
    private static final int MAX_VERSION_LENGTH = 48;
    private static final int MAX_ERROR_LENGTH = 500;

    private final ReleaseService releaseService;
    private final UpdateReportRepository updateReportRepository;

    /** 检查更新：has_update=true 时返回端侧解析器写死的键名集合。 */
    public Map<String, Object> check(String platform, String currentVersion, String channel, String pteid) {
        String pf = norm(platform, ReleaseService.PLATFORMS, "windows");
        String ch = norm(channel, ReleaseService.CHANNELS, "stable");
        String current = currentVersion == null || currentVersion.isBlank() ? "0.0.0" : currentVersion.trim();

        Optional<ReleaseInfo> latest = releaseService.latestPublished(pf, ch);
        if (latest.isEmpty() || ReleaseService.compareVersions(latest.get().getVersion(), current) <= 0) {
            return noUpdate(pf, current);
        }
        ReleaseInfo r = latest.get();
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
        log.debug("更新检查 platform={} channel={} current={} latest={} pteid={}",
                pf, ch, current, r.getVersion(), pteid);
        return m;
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