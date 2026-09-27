package com.potatotv.pacc.service.v53;

import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.PlayerDailyPattern;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.PlayerDailyPatternRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import com.potatotv.pacc.service.detection.v52.ReputationV2Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * v5.3 管理端补齐：把 v5.2 已有的画像 / 指纹 / 作息 / 信誉数据聚合成管理端可直接渲染的只读视图。
 *
 * <p>只做查询与拼装，不写库、不改动 v5.2 任何既有接口与语义：
 * <ul>
 *   <li>{@link #deviceFingerprints} → 硬件指纹管理页（§2.5）</li>
 *   <li>{@link #behaviorTrend} → 行为画像页的趋势区（逐日在线时长 / 事件量）</li>
 *   <li>{@link #reputationOverview} → 信誉管理页的全量分布与策略（§2.3）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminInsightService {

    /** 指纹列表上限（一次查询的行数）。 */
    private static final int FINGERPRINT_LIMIT = 300;
    /** 单个指纹最多回列几个关联 PTEID（避免长尾把响应撑爆）。 */
    private static final int SHARED_PTEID_PREVIEW = 10;
    /** 趋势默认与上限天数。 */
    private static final int TREND_DEFAULT_DAYS = 30;
    private static final int TREND_MAX_DAYS = 90;

    private final PlayerHardwareFingerprintRepository fingerprints;
    private final DeviceRecordRepository devices;
    private final PlayerBehaviorProfileRepository profiles;
    private final PlayerDailyPatternRepository dailyPatterns;

    // ------------------------------ §2.5 硬件指纹管理 ------------------------------

    /**
     * 指纹明细列表（按最近出现时间倒序，最多 {@value #FINGERPRINT_LIMIT} 条）。
     *
     * @param pteid      按玩家过滤，空则取全局
     * @param sharedOnly 只看多账号共用的指纹
     */
    public Map<String, Object> deviceFingerprints(String pteid, boolean sharedOnly) {
        List<PlayerHardwareFingerprint> rows = (pteid == null || pteid.isBlank())
                ? fingerprints.findTop300ByOrderByLastSeenAtDesc()
                : fingerprints.findTop300ByPteidOrderByLastSeenAtDesc(pteid);

        Set<String> hashes = new LinkedHashSet<>();
        for (PlayerHardwareFingerprint row : rows) {
            hashes.add(row.getFingerprintHash());
        }
        // 一次查出这些摘要的全部持有者，避免逐行 count 造成 N+1
        Map<String, List<String>> holders = new HashMap<>();
        if (!hashes.isEmpty()) {
            for (PlayerHardwareFingerprint f : fingerprints.findByFingerprintHashIn(hashes)) {
                List<String> list = holders.computeIfAbsent(f.getFingerprintHash(), k -> new ArrayList<>());
                if (!list.contains(f.getPteid())) list.add(f.getPteid());
            }
        }
        Set<String> mutation = new HashSet<>(fingerprints.findMutationPteids());
        Map<String, String> platform = latestPlatforms(rows);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        int sharedTotal = 0;
        for (PlayerHardwareFingerprint row : rows) {
            List<String> shared = holders.getOrDefault(row.getFingerprintHash(), List.of(row.getPteid()));
            boolean sharedAccount = shared.size() > 1;
            if (sharedAccount) sharedTotal++;
            if (sharedOnly && !sharedAccount) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fingerprint_hash", row.getFingerprintHash());
            m.put("pteid", row.getPteid());
            m.put("first_seen_at", iso(row.getFirstSeenAt()));
            m.put("last_seen_at", iso(row.getLastSeenAt()));
            m.put("seen_count", row.getSeenCount());
            m.put("platform", platform.getOrDefault(row.getPteid(), ""));
            m.put("shared_count", shared.size());
            m.put("shared_pteids", shared.size() > SHARED_PTEID_PREVIEW
                    ? shared.subList(0, SHARED_PTEID_PREVIEW) : shared);
            m.put("shared_account", sharedAccount);
            m.put("mutation", mutation.contains(row.getPteid()));
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("listed", rows.size());
        out.put("shared_total", sharedTotal);
        out.put("mutation_total", mutation.size());
        out.put("limit", FINGERPRINT_LIMIT);
        out.put("truncated", rows.size() >= FINGERPRINT_LIMIT);
        return out;
    }

    /** 每个玩家最近一次登录的平台（取自 t_device，与指纹摘要不是同一个值，仅作环境参考）。 */
    private Map<String, String> latestPlatforms(List<PlayerHardwareFingerprint> rows) {
        Set<String> pteids = new LinkedHashSet<>();
        for (PlayerHardwareFingerprint row : rows) {
            pteids.add(row.getPteid());
        }
        Map<String, String> out = new HashMap<>();
        if (pteids.isEmpty()) return out;
        Map<String, Instant> last = new HashMap<>();
        for (DeviceRecord d : devices.findByPteidIn(pteids)) {
            Instant at = d.getLastLoginAt();
            Instant prev = last.get(d.getPteid());
            if (prev == null || (at != null && at.isAfter(prev))) {
                if (at != null) last.put(d.getPteid(), at);
                out.put(d.getPteid(), d.getPlatform() == null ? "" : d.getPlatform());
            }
        }
        return out;
    }

    // ------------------------------ §2.2 行为趋势 ------------------------------

    /**
     * 行为趋势：逐日在线时长与事件量（数据源 {@code t_player_daily_pattern}），外加当前画像基线。
     *
     * <p>CPS / 瞄准平滑度等基线指标只存聚合值（画像表无逐日明细），因此趋势给的是「作息与事件」，
     * 基线值作为水平参考一并返回，管理端不得把基线值画成时间序列。</p>
     */
    public Map<String, Object> behaviorTrend(String pteid, int days) {
        int d = Math.max(1, Math.min(TREND_MAX_DAYS, days <= 0 ? TREND_DEFAULT_DAYS : days));
        LocalDate from = LocalDate.now().minusDays(d - 1L);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        long[] byHour = new long[24];
        if (pteid != null && !pteid.isBlank()) {
            for (PlayerDailyPattern p : dailyPatterns.findByPteidOrderByPatternDateDescHourOfDayAsc(pteid)) {
                if (p.getPatternDate() == null || p.getPatternDate().isBefore(from)) continue;
                long[] agg = byDay.computeIfAbsent(p.getPatternDate(), k -> new long[2]);
                agg[0] += p.getSessionSeconds();
                agg[1] += p.getEventCount();
                int hour = p.getHourOfDay();
                if (hour >= 0 && hour < 24) byHour[hour] += p.getSessionSeconds();
            }
        }

        List<Map<String, Object>> daily = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, long[]> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey().toString());
            m.put("session_seconds", e.getValue()[0]);
            m.put("online_hours", round2(e.getValue()[0] / 3600.0));
            m.put("event_count", e.getValue()[1]);
            daily.add(m);
        }
        List<Map<String, Object>> hourly = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("session_seconds", byHour[h]);
            m.put("online_hours", round2(byHour[h] / 3600.0));
            hourly.add(m);
        }

        PlayerBehaviorProfile p = (pteid == null || pteid.isBlank())
                ? null : profiles.findById(pteid).orElse(null);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("exists", p != null);
        if (p != null) {
            baseline.put("mean_cps", p.getMeanCps());
            baseline.put("cps_std", p.getCpsStd());
            baseline.put("mean_aim_smoothness", p.getMeanAimSmoothness());
            baseline.put("aim_smoothness_std", p.getAimSmoothnessStd());
            baseline.put("mean_speed", p.getMeanSpeed());
            baseline.put("speed_std", p.getSpeedStd());
            baseline.put("sample_count", p.getSampleCount());
            baseline.put("total_sessions", p.getTotalSessions());
            baseline.put("total_detections", p.getTotalDetections());
            baseline.put("false_positives", p.getFalsePositives());
            baseline.put("reputation_score", p.getReputationScore());
            baseline.put("reputation_level", p.getReputationLevel());
            baseline.put("profile_version", p.getProfileVersion());
            baseline.put("first_seen_at", iso(p.getFirstSeenAt()));
            baseline.put("updated_at", iso(p.getUpdatedAt()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid == null ? "" : pteid);
        out.put("days", d);
        out.put("daily", daily);
        out.put("hourly", hourly);
        out.put("baseline", baseline);
        return out;
    }

    // ------------------------------ §2.3 信誉全量分布与策略 ------------------------------

    /** 全量信誉分布（按等级聚合，一次查询）、各等级检测策略与计分规则。 */
    public Map<String, Object> reputationOverview() {
        long total = profiles.count();
        Map<String, long[]> byLevel = new HashMap<>();
        double weighted = 0;
        long counted = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Object[] row : profiles.summaryByLevel()) {
            String level = row[0] == null ? "" : row[0].toString();
            long count = row[1] == null ? 0 : ((Number) row[1]).longValue();
            double avg = row[2] == null ? 0 : ((Number) row[2]).doubleValue();
            if (row[3] != null) min = Math.min(min, ((Number) row[3]).intValue());
            if (row[4] != null) max = Math.max(max, ((Number) row[4]).intValue());
            byLevel.put(level, new long[]{count, Math.round(avg)});
            weighted += avg * count;
            counted += count;
        }

        List<Map<String, Object>> buckets = new ArrayList<>(5);
        for (LevelBand band : LevelBand.ORDERED) {
            long[] agg = byLevel.getOrDefault(band.level, new long[]{0, 0});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", band.level);
            m.put("min_score", band.min);
            m.put("max_score", band.max);
            m.put("count", agg[0]);
            m.put("average_score", agg[1]);
            m.put("share", total == 0 ? 0.0 : round4(agg[0] / (double) total));
            m.put("sample_policy", policyView(band.min));
            buckets.add(m);
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        for (ReputationV2Service.Event e : ReputationV2Service.Event.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", e.name());
            m.put("label", e.label());
            m.put("delta", e.delta());
            rules.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("buckets", buckets);
        out.put("rules", rules);
        out.put("initial_score", ReputationV2Service.INITIAL_SCORE);
        out.put("min_score", ReputationV2Service.MIN);
        out.put("max_score", ReputationV2Service.MAX);
        out.put("clean_hour_bonus_cap", ReputationV2Service.CLEAN_HOUR_BONUS_CAP);
        out.put("average_score", counted == 0 ? 0 : Math.round(weighted / counted));
        out.put("lowest_score", min == Integer.MAX_VALUE ? 0 : min);
        out.put("highest_score", max == Integer.MIN_VALUE ? 0 : max);
        return out;
    }

    /** 等级区间（与 {@code PlayerBehaviorProfile.levelOf} 保持一致）。 */
    private enum LevelBand {
        HIGH_RISK(PlayerBehaviorProfile.LEVEL_HIGH_RISK, 0, ReputationV2Service.LEVEL_RISK_MIN - 1),
        RISK(PlayerBehaviorProfile.LEVEL_RISK, ReputationV2Service.LEVEL_RISK_MIN, ReputationV2Service.LEVEL_OBSERVED_MIN - 1),
        OBSERVED(PlayerBehaviorProfile.LEVEL_OBSERVED, ReputationV2Service.LEVEL_OBSERVED_MIN, ReputationV2Service.LEVEL_NORMAL_MIN - 1),
        NORMAL(PlayerBehaviorProfile.LEVEL_NORMAL, ReputationV2Service.LEVEL_NORMAL_MIN, ReputationV2Service.LEVEL_TRUSTED_MIN - 1),
        TRUSTED(PlayerBehaviorProfile.LEVEL_TRUSTED, ReputationV2Service.LEVEL_TRUSTED_MIN, PlayerBehaviorProfile.SCORE_MAX);

        /** 由低到高，画图时从最危险的一端开始。 */
        static final List<LevelBand> ORDERED = List.of(values());

        final String level;
        final int min;
        final int max;

        LevelBand(String level, int min, int max) {
            this.level = level;
            this.min = min;
            this.max = max;
        }
    }

    private static Map<String, Object> policyView(int score) {
        ReputationV2Service.ReputationPolicy p = ReputationV2Service.policy(score);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threshold_percent_delta", p.thresholdPercentDelta());
        m.put("threshold_multiplier", p.thresholdMultiplier());
        m.put("l0_only", p.l0Only());
        m.put("force_l2", p.forceL2());
        m.put("full_feature_report", p.fullFeatureReport());
        return m;
    }

    private static String iso(Instant at) {
        return at == null ? "" : at.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}