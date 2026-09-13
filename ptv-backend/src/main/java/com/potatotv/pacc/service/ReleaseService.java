package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ReleaseInfo;
import com.potatotv.pacc.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 版本发布管理：按平台×渠道维护发布记录，支持草稿->发布->归档、灰度放量与强制更新标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseService {

    private static final List<String> PLATFORMS = List.of("windows", "android", "ios", "macos", "linux");
    private static final List<String> CHANNELS = List.of("stable", "beta", "canary");

    private final ReleaseRepository releaseRepository;

    /** 新建草稿。 */
    @Transactional
    public ReleaseInfo create(String platform, String channel, String version, int buildNo,
                              String notes, String downloadUrl, String sha256, String operator) {
        String pf = norm(platform, PLATFORMS, "windows");
        String ch = norm(channel, CHANNELS, "stable");
        ReleaseInfo r = ReleaseInfo.builder()
                .id(UUID.randomUUID().toString())
                .platform(pf)
                .channel(ch)
                .version(version == null || version.isBlank() ? "0.0.0" : version.trim())
                .buildNo(buildNo)
                .notes(notes)
                .downloadUrl(downloadUrl)
                .sha256(sha256)
                .status("DRAFT")
                .createdBy(operator)
                .createdAt(Instant.now())
                .build();
        releaseRepository.save(r);
        log.info("新建发布草稿 {}/{} {} (build {}) by={}", pf, ch, r.getVersion(), buildNo, operator);
        return r;
    }

    /** 更新发布记录（草稿/已发布均可编辑元数据）。 */
    @Transactional
    public ReleaseInfo update(String id, Map<String, Object> body) {
        ReleaseInfo r = releaseRepository.findById(id).orElse(null);
        if (r == null) return null;
        if (body.containsKey("notes")) r.setNotes(str(body.get("notes")));
        if (body.containsKey("download_url")) r.setDownloadUrl(str(body.get("download_url")));
        if (body.containsKey("sha256")) r.setSha256(str(body.get("sha256")));
        if (body.containsKey("min_app_version")) r.setMinAppVersion(str(body.get("min_app_version")));
        if (body.containsKey("manual_enabled")) r.setManualEnabled(bool(body.get("manual_enabled")));
        if (body.containsKey("forced_enabled")) r.setForcedEnabled(bool(body.get("forced_enabled")));
        if (body.containsKey("crash_rate_pct")) {
            try { r.setCrashRatePct(new BigDecimal(String.valueOf(body.get("crash_rate_pct")))); }
            catch (Exception ignored) { /* 非法数值忽略 */ }
        }
        releaseRepository.save(r);
        return r;
    }

    /** 发布：状态置 PUBLISHED，记录发布时间。 */
    @Transactional
    public ReleaseInfo publish(String id, String operator) {
        ReleaseInfo r = releaseRepository.findById(id).orElse(null);
        if (r == null) return null;
        r.setStatus("PUBLISHED");
        r.setPublishedAt(Instant.now());
        releaseRepository.save(r);
        log.info("发布版本 {}/{} {}={} by={}", r.getPlatform(), r.getChannel(), r.getVersion(), operator);
        return r;
    }

    /** 归档。 */
    @Transactional
    public boolean archive(String id) {
        ReleaseInfo r = releaseRepository.findById(id).orElse(null);
        if (r == null) return false;
        r.setStatus("ARCHIVED");
        releaseRepository.save(r);
        return true;
    }

    /** 删除（仅草稿/已归档）。 */
    @Transactional
    public boolean remove(String id) {
        ReleaseInfo r = releaseRepository.findById(id).orElse(null);
        if (r == null) return false;
        if ("PUBLISHED".equals(r.getStatus())) return false;
        releaseRepository.delete(r);
        return true;
    }

    /** 列表（可按平台×渠道过滤）。 */
    public List<Map<String, Object>> list(String platform, String channel) {
        List<ReleaseInfo> rows;
        if (platform != null && !platform.isBlank() && channel != null && !channel.isBlank()) {
            rows = releaseRepository.findByPlatformAndChannelOrderByBuildNoDesc(platform.trim(), channel.trim());
        } else {
            rows = releaseRepository.findAllByOrderByCreatedAtDesc();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReleaseInfo r : rows) {
            out.add(toView(r));
        }
        return out;
    }

    private Map<String, Object> toView(ReleaseInfo r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("platform", r.getPlatform());
        m.put("channel", r.getChannel());
        m.put("version", r.getVersion());
        m.put("build_no", r.getBuildNo());
        m.put("notes", r.getNotes());
        m.put("download_url", r.getDownloadUrl());
        m.put("sha256", r.getSha256());
        m.put("min_app_version", r.getMinAppVersion());
        m.put("manual_enabled", r.isManualEnabled());
        m.put("forced_enabled", r.isForcedEnabled());
        m.put("crash_rate_pct", r.getCrashRatePct());
        m.put("status", r.getStatus());
        m.put("published_at", r.getPublishedAt() == null ? "" : r.getPublishedAt().toString());
        m.put("created_at", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
        return m;
    }

    private String norm(String v, List<String> allowed, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        String low = v.trim().toLowerCase();
        return allowed.contains(low) ? low : fallback;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private boolean bool(Object o) { return o instanceof Boolean b && b; }
}