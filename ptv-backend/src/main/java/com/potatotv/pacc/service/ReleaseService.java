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
import java.util.Optional;
import java.util.UUID;

/**
 * 版本发布管理：按平台×渠道维护发布记录，支持草稿->发布->归档、灰度放量与强制更新标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseService {

    static final List<String> PLATFORMS =
            List.of("windows", "android", "ios", "harmony", "linux", "macos");
    /** 通道：stable/beta/alpha/dev 为设计文档 §4.8 定义；canary 为历史通道，保留以免既有数据失配。 */
    static final List<String> CHANNELS = List.of("stable", "beta", "alpha", "dev", "canary");

    private final ReleaseRepository releaseRepository;

    /** 新建草稿。 */
    @Transactional
    public ReleaseInfo create(String platform, String channel, String version, int buildNo,
                              String notes, String downloadUrl, String sha256, String operator,
                              Map<String, Object> extra) {
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
        applyPackageFields(r, extra);
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
        applyPackageFields(r, body);
        releaseRepository.save(r);
        return r;
    }

    /** 全量包体积/签名与差分包元数据：create 与 update 共用，缺键不改写。 */
    private void applyPackageFields(ReleaseInfo r, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("file_size")) r.setFileSize(lng(body.get("file_size")));
        if (body.containsKey("signature")) r.setSignature(str(body.get("signature")));
        if (body.containsKey("delta_from_version")) r.setDeltaFromVersion(str(body.get("delta_from_version")));
        if (body.containsKey("delta_url")) r.setDeltaUrl(str(body.get("delta_url")));
        if (body.containsKey("delta_sha256")) r.setDeltaSha256(str(body.get("delta_sha256")));
        if (body.containsKey("delta_size")) r.setDeltaSize(lng(body.get("delta_size")));
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

    /**
     * 取给定 platform×channel 下 status=PUBLISHED 的最高语义化版本。
     * 版本号可能带 v 前缀或 - 预发布后缀，一律按 major.minor.patch 数值比较，不用字符串排序
     * （否则 5.10.0 会被排到 5.9.9 之前）。
     */
    public Optional<ReleaseInfo> latestPublished(String platform, String channel) {
        String pf = norm(platform, PLATFORMS, "windows");
        String ch = norm(channel, CHANNELS, "stable");
        ReleaseInfo best = null;
        for (ReleaseInfo r : releaseRepository.findByPlatformAndChannelAndStatus(pf, ch, "PUBLISHED")) {
            if (best == null || compareVersions(r.getVersion(), best.getVersion()) > 0) {
                best = r;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 语义化版本比较：>=0 表示 a 不低于 b。非法/缺段按 0 处理。 */
    static int compareVersions(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(pa[i], pb[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] parseVersion(String v) {
        int[] out = {0, 0, 0};
        if (v == null || v.isBlank()) return out;
        String s = v.trim();
        if (s.charAt(0) == 'v' || s.charAt(0) == 'V') s = s.substring(1);
        int cut = s.indexOf('-');
        if (cut >= 0) s = s.substring(0, cut);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        String[] parts = s.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) out[i] = leadingInt(parts[i]);
        return out;
    }

    private static int leadingInt(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return 0;
        try { return Integer.parseInt(part.substring(0, end)); }
        catch (NumberFormatException e) { return Integer.MAX_VALUE; }
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
        m.put("file_size", r.getFileSize());
        m.put("signature", r.getSignature());
        m.put("delta_from_version", r.getDeltaFromVersion());
        m.put("delta_url", r.getDeltaUrl());
        m.put("delta_sha256", r.getDeltaSha256());
        m.put("delta_size", r.getDeltaSize());
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

    private long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? 0L : Long.parseLong(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private boolean bool(Object o) { return o instanceof Boolean b && b; }
}