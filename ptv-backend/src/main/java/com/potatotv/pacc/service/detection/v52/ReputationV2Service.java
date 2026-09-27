package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.ReputationLog;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.V52ReputationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v5.2 §7.2 信誉系统（0-1000 分制）。
 *
 * <p><b>与既有 {@code ReputationService} 的关系</b>：不改写既有实现（{@link com.potatotv.pacc.service.ReputationService}
 * 仍按 0-100 维护 {@code Account.reputation} 与个人权益），本服务是叠加在其上的新版口径：
 * 权威分值落在 {@link PlayerBehaviorProfile#getReputationScore()}（初始 {@value #INITIAL_SCORE}，范围 0-1000）。</p>
 *
 * <p><b>0-100 → 0-1000 兼容规则（不做破坏性改写）</b>：
 * <ul>
 *   <li>历史行（{@code t_account.reputation}、{@code t_reputation_log}）保持 0-100 口径，一律不动；</li>
 *   <li>v5.2 的审计仍写入同一张 {@code t_reputation_log}，但 {@code source} 以 {@value #SOURCE_PREFIX}
 *       前缀标记来源事件；</li>
 *   <li>读取端据前缀区分口径：非 {@code v52:} 前缀为 0-100，用 {@link #legacyScale(int)} 换算到 0-1000
 *       后与新版比较。</li>
 * </ul></p>
 *
 * <p><b>幂等</b>：每次调整以「来源事件 id」编入 {@code source}（形如
 * {@code v52:appeal|<eventId>}），同一事件重复提交只记一次，杜绝重复加分。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationV2Service {

    /** 分值下限。 */
    public static final int MIN = PlayerBehaviorProfile.SCORE_MIN;
    /** 分值上限。 */
    public static final int MAX = PlayerBehaviorProfile.SCORE_MAX;
    /** 初始分值。 */
    public static final int INITIAL_SCORE = PlayerBehaviorProfile.SCORE_INITIAL;

    /** 无检测小时奖励：每 1 小时无检测 +1 分。 */
    public static final int DELTA_CLEAN_HOUR = 1;
    /** 无检测小时奖励的累计上限：自初始分起最多 +200（对应 200 个小时）。 */
    public static final int CLEAN_HOUR_BONUS_CAP = 200;
    /** 确认红屏扣分。 */
    public static final int DELTA_CONFIRMED_REDSCREEN = -50;
    /** 申诉成功（复核为误报）加分。 */
    public static final int DELTA_APPEAL_APPROVED = 20;
    /** 硬件指纹突变扣分。 */
    public static final int DELTA_FINGERPRINT_MUTATION = -30;
    /** 新设备登录扣分。 */
    public static final int DELTA_NEW_DEVICE_LOGIN = -10;

    /** 等级下限：信任 900-1000。 */
    public static final int LEVEL_TRUSTED_MIN = 900;
    /** 等级下限：正常 700-899。 */
    public static final int LEVEL_NORMAL_MIN = 700;
    /** 等级下限：观察 500-699。 */
    public static final int LEVEL_OBSERVED_MIN = 500;
    /** 等级下限：风险 300-499。 */
    public static final int LEVEL_RISK_MIN = 300;

    /** v5.2 审计来源前缀：读取端据此区分 0-100（历史）与 0-1000（v5.2）口径。 */
    public static final String SOURCE_PREFIX = "v52:";
    /** source 列长度上限（t_reputation_log.source VARCHAR(64)）。 */
    private static final int SOURCE_MAX = 64;
    /** 编入 source 的来源事件 id 最大长度（超出截断，避免撑破 source 列）。 */
    private static final int EVENT_ID_MAX = 40;
    /** reason 列长度上限（t_reputation_log.reason VARCHAR(128)）。 */
    private static final int REASON_MAX = 120;

    /** 计入审计的来源事件类型及其分值变动。 */
    public enum Event {
        /** 无检测小时奖励。 */
        CLEAN_HOUR("hour", DELTA_CLEAN_HOUR, "无检测小时"),
        /** 确认红屏。 */
        CONFIRMED_REDSCREEN("redscreen", DELTA_CONFIRMED_REDSCREEN, "确认红屏"),
        /** 申诉成功（判定误报）。 */
        APPEAL_APPROVED("appeal", DELTA_APPEAL_APPROVED, "申诉成功（误报）"),
        /** 硬件指纹突变。 */
        FINGERPRINT_MUTATION("fingerprint", DELTA_FINGERPRINT_MUTATION, "硬件指纹突变"),
        /** 新设备登录。 */
        NEW_DEVICE_LOGIN("device", DELTA_NEW_DEVICE_LOGIN, "新设备登录");

        private final String key;
        private final int delta;
        private final String label;

        Event(String key, int delta, String label) {
            this.key = key;
            this.delta = delta;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public int delta() {
            return delta;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 等级检测策略：按信誉等级对检测阈值与上报行为的调整。
     *
     * @param thresholdPercentDelta 阈值百分比增量（+20 表示放宽 20%，-30 表示收紧 30%）
     * @param l0Only                是否仅执行 L0 规则（信任玩家）
     * @param forceL2               是否强制 L2 深度检测
     * @param fullFeatureReport     是否每局全量上报特征（高危玩家）
     */
    public record ReputationPolicy(int thresholdPercentDelta, boolean l0Only, boolean forceL2,
                                   boolean fullFeatureReport) {

        /** 阈值倍率 = 1 + delta%。 */
        public double thresholdMultiplier() {
            return 1.0 + thresholdPercentDelta / 100.0;
        }
    }

    private final PlayerBehaviorProfileRepository profileRepository;
    private final V52ReputationLogRepository logRepository;

    /** 当前 0-1000 信誉分；无画像行时返回初始分。 */
    public int score(String pteid) {
        if (pteid == null || pteid.isBlank()) {
            return INITIAL_SCORE;
        }
        return profileRepository.findById(pteid)
                .map(PlayerBehaviorProfile::getReputationScore)
                .map(ReputationV2Service::clamp)
                .orElse(INITIAL_SCORE);
    }

    /**
     * 按既定规则应用一次信誉变动并落审计（幂等）。
     *
     * @param pteid   玩家
     * @param event   事件类型（决定分值变动）
     * @param eventId 来源事件 id（申诉 id / 告警 id / 指纹摘要等）；为空则不做幂等去重
     * @param reason  自定义原因；为空时使用事件默认文案
     * @return 是否实际记账（幂等命中或达到奖励上限时返回 false）
     */
    @Transactional
    public boolean apply(String pteid, Event event, String eventId, String reason) {
        if (pteid == null || pteid.isBlank() || event == null) {
            return false;
        }
        String source = sourceOf(event.key(), eventId);
        if (eventId != null && !eventId.isBlank() && logRepository.existsBySource(source)) {
            log.debug("信誉调整幂等跳过 pteid={} source={}", pteid, source);
            return false;
        }
        if (event == Event.CLEAN_HOUR
                && logRepository.countByPteidAndSourceStartingWith(pteid, SOURCE_PREFIX + event.key())
                >= CLEAN_HOUR_BONUS_CAP) {
            // 无检测小时奖励已达累计上限，不再记账（保持审计表精简）
            return false;
        }
        String text = (reason == null || reason.isBlank()) ? event.label() : reason;
        return applyDelta(pteid, event.delta(), source, text);
    }

    /**
     * 人工调整（管理端）：必须给出原因，全程审计。
     *
     * @param delta 调整量（0-1000 口径，可正可负）
     * @return 是否实际记账
     */
    @Transactional
    public boolean adjustManual(String pteid, int delta, String reason, String actor) {
        if (pteid == null || pteid.isBlank()) {
            throw new IllegalArgumentException("pteid 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("人工调整必须填写原因");
        }
        String source = sourceOf("manual", UUID.randomUUID().toString());
        String text = "人工调整：" + reason + (actor == null || actor.isBlank() ? "" : "（操作人 " + actor + "）");
        return applyDelta(pteid, delta, source, text);
    }

    /** 按分值推导检测策略（纯函数，便于单测）。 */
    public static ReputationPolicy policy(int score) {
        int s = clamp(score);
        if (s >= LEVEL_TRUSTED_MIN) {
            // 信任：阈值放宽 20%，仅 L0 规则
            return new ReputationPolicy(20, true, false, false);
        }
        if (s >= LEVEL_NORMAL_MIN) {
            return new ReputationPolicy(0, false, false, false);
        }
        if (s >= LEVEL_OBSERVED_MIN) {
            // 观察：阈值收紧 10%
            return new ReputationPolicy(-10, false, false, false);
        }
        if (s >= LEVEL_RISK_MIN) {
            // 风险：阈值收紧 20%，强制 L2
            return new ReputationPolicy(-20, false, true, false);
        }
        // 高危：阈值收紧 30%，强制 L2，且每局全量上报特征
        return new ReputationPolicy(-30, false, true, true);
    }

    /** 某玩家的检测策略（读取其分值后按 {@link #policy(int)} 推导）。 */
    public ReputationPolicy policy(String pteid) {
        return policy(score(pteid));
    }

    /** 分值 → 等级（边界 900/700/500/300）。 */
    public static String levelFor(int score) {
        return PlayerBehaviorProfile.levelOf(clamp(score));
    }

    /** 0-1000 分值 → 兼容 0-100 口径（四舍五入），供与历史数据比对。 */
    public static int legacyScale(int score) {
        return (int) Math.round(clamp(score) / 10.0);
    }

    /** 管理端/玩家端视图：分值、等级、策略与最近审计明细。 */
    public Map<String, Object> detail(String pteid, int limit) {
        int score = score(pteid);
        ReputationPolicy pol = policy(score);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid);
        out.put("score", score);
        out.put("max_score", MAX);
        out.put("level", levelFor(score));
        out.put("legacy_scale_score", legacyScale(score));
        Map<String, Object> policyView = new LinkedHashMap<>();
        policyView.put("threshold_percent_delta", pol.thresholdPercentDelta());
        policyView.put("threshold_multiplier", pol.thresholdMultiplier());
        policyView.put("l0_only", pol.l0Only());
        policyView.put("force_l2", pol.forceL2());
        policyView.put("full_feature_report", pol.fullFeatureReport());
        out.put("policy", policyView);
        out.put("logs", recentLogs(pteid, limit));
        return out;
    }

    /** 最近审计明细（仅 v5.2 口径行；历史行属 0-100 口径，不在此混排）。 */
    public List<Map<String, Object>> recentLogs(String pteid, int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        if (pteid == null || pteid.isBlank()) {
            return out;
        }
        for (ReputationLog l : logRepository.findByPteidAndSourceStartingWithOrderByCreatedAtDesc(
                pteid, SOURCE_PREFIX, Pageable.ofSize(n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("delta", l.getDelta());
            m.put("score_after", l.getScoreAfter());
            m.put("reason", l.getReason());
            m.put("source", l.getSource());
            m.put("scale", "0-1000");
            m.put("created_at", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    // ------------------------------ 内部实现 ------------------------------

    /** 落分并写审计行；不存在的画像行按初始分建行。 */
    private boolean applyDelta(String pteid, int delta, String source, String reason) {
        PlayerBehaviorProfile p = profileRepository.findById(pteid)
                .orElseGet(() -> BehaviorProfileService.newProfile(pteid, Instant.now()));
        int before = clamp(p.getReputationScore());
        int after = clamp(before + delta);
        p.setReputationScore(after);
        p.setReputationLevel(levelFor(after));
        p.setUpdatedAt(Instant.now());
        profileRepository.save(p);

        logRepository.save(ReputationLog.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .delta(after - before)
                .scoreAfter(after)
                .reason(truncate(reason, REASON_MAX))
                .source(truncate(source, SOURCE_MAX))
                .createdAt(Instant.now())
                .build());
        log.info("信誉 v5.2 调整 pteid={} source={} {}±{} -> {}", pteid, source, before, after - before, after);
        return true;
    }

    /** 组装幂等的来源事件标识：{@code v52:<key>|<eventId>}；eventId 为空时用随机串（不去重）。 */
    private static String sourceOf(String key, String eventId) {
        String base = SOURCE_PREFIX + key + "|";
        if (eventId == null || eventId.isBlank()) {
            return base + UUID.randomUUID();
        }
        return base + truncate(eventId, EVENT_ID_MAX);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int clamp(int score) {
        return PlayerBehaviorProfile.clampScore(score);
    }
}