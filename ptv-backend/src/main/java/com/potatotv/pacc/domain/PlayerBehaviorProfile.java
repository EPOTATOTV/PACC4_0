package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.2 §6.2 玩家行为画像：由在线统计（Welford）从观测到的特征向量折叠出的个人行为基线。
 *
 * <p>只存统计量（在线均值与总体标准差、样本数），<b>不落原始特征样本</b>——因此观测次数再多，
 * 存储也只是常数级；标准差按总体口径（除以 n）由 Welford 的 M2 直接推导。</p>
 *
 * <p>{@link #stability} 为画像稳定度 0-1：随样本数与观测时长增长，约 10 小时观测后趋于 1.0
 * （见 {@code BehaviorProfileService#observe}）。</p>
 *
 * <p>信誉分 {@link #reputationScore} 为 v5.2 口径（0-1000，初始 600），与既有
 * {@link Account#getReputation()}（0-100）并存；兼容与换算规则见
 * {@code ReputationV2Service}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_player_behavior_profile", indexes = {
        @Index(name = "idx_behavior_profile_score", columnList = "reputation_score"),
        @Index(name = "idx_behavior_profile_updated", columnList = "updated_at")
})
public class PlayerBehaviorProfile {

    /** 信誉等级：信任（900-1000）。 */
    public static final String LEVEL_TRUSTED = "TRUSTED";
    /** 信誉等级：正常（700-899）。 */
    public static final String LEVEL_NORMAL = "NORMAL";
    /** 信誉等级：观察（500-699）。 */
    public static final String LEVEL_OBSERVED = "OBSERVED";
    /** 信誉等级：风险（300-499）。 */
    public static final String LEVEL_RISK = "RISK";
    /** 信誉等级：高危（0-299）。 */
    public static final String LEVEL_HIGH_RISK = "HIGH_RISK";

    /** 信誉分下限。 */
    public static final int SCORE_MIN = 0;
    /** 信誉分上限。 */
    public static final int SCORE_MAX = 1000;
    /** 新玩家初始信誉分。 */
    public static final int SCORE_INITIAL = 600;

    /** 当前画像结构版本。 */
    public static final int CURRENT_PROFILE_VERSION = 1;

    @Id
    @Column(length = 64, nullable = false)
    private String pteid;

    /** 点击速度在线均值（{@code feature_click_cps}）。 */
    @Builder.Default
    @Column(name = "mean_cps", nullable = false)
    private double meanCps = 0.0;

    /** 点击速度总体标准差。 */
    @Builder.Default
    @Column(name = "cps_std", nullable = false)
    private double cpsStd = 0.0;

    /** 瞄准平滑度在线均值（{@code feature_aim_smoothness}）。 */
    @Builder.Default
    @Column(name = "mean_aim_smoothness", nullable = false)
    private double meanAimSmoothness = 0.0;

    /** 瞄准平滑度总体标准差。 */
    @Builder.Default
    @Column(name = "aim_smoothness_std", nullable = false)
    private double aimSmoothnessStd = 0.0;

    /** 移动速度比在线均值（{@code feature_speed_ratio}）。 */
    @Builder.Default
    @Column(name = "mean_speed", nullable = false)
    private double meanSpeed = 0.0;

    /** 移动速度比总体标准差。 */
    @Builder.Default
    @Column(name = "speed_std", nullable = false)
    private double speedStd = 0.0;

    /** 累计会话数。 */
    @Builder.Default
    @Column(name = "total_sessions", nullable = false)
    private long totalSessions = 0L;

    /** 累计检测命中次数。 */
    @Builder.Default
    @Column(name = "total_detections", nullable = false)
    private long totalDetections = 0L;

    /** 其中经复核确认为误报的次数。 */
    @Builder.Default
    @Column(name = "false_positives", nullable = false)
    private long falsePositives = 0L;

    /** v5.2 信誉分 0-1000（初始 600）。 */
    @Builder.Default
    @Column(name = "reputation_score", nullable = false)
    private int reputationScore = SCORE_INITIAL;

    /** 信誉等级（TRUSTED / NORMAL / OBSERVED / RISK / HIGH_RISK）。 */
    @Builder.Default
    @Column(name = "reputation_level", nullable = false, length = 16)
    private String reputationLevel = LEVEL_OBSERVED;

    /** 画像结构版本。 */
    @Builder.Default
    @Column(name = "profile_version", nullable = false)
    private int profileVersion = CURRENT_PROFILE_VERSION;

    /** 已折叠的观测样本数。 */
    @Builder.Default
    @Column(name = "sample_count", nullable = false)
    private long sampleCount = 0L;

    /** 画像稳定度 0-1。 */
    @Builder.Default
    @Column(nullable = false)
    private double stability = 0.0;

    @Builder.Default
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** 信誉等级由分值推导；边界 900/700/500/300。 */
    public static String levelOf(int score) {
        if (score >= 900) return LEVEL_TRUSTED;
        if (score >= 700) return LEVEL_NORMAL;
        if (score >= 500) return LEVEL_OBSERVED;
        if (score >= 300) return LEVEL_RISK;
        return LEVEL_HIGH_RISK;
    }

    /** 分值裁剪到 [0,1000]。 */
    public static int clampScore(int score) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, score));
    }
}