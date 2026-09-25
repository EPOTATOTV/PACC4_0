package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.PlayerDailyPattern;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.PlayerDailyPatternRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import com.potatotv.pacc.service.detection.v46.ZeroDayAnomalyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.2 §6.2 行为画像系统：把观测到的特征向量折叠进玩家的个人行为基线，并据此给出自适应检测阈值。
 *
 * <p><b>在线统计</b>：{@link #observe} 用 Welford 递推维护三项跟踪特征
 * （{@code feature_click_cps} / {@code feature_aim_smoothness} / {@code feature_speed_ratio}）
 * 的在线均值与总体标准差，<b>只存统计量，不存原始样本</b>——标准差由递推的 M2 推出
 * （{@code M2 = n·σ²}，写库时只落 σ，读回重建 M2 继续递推）。</p>
 *
 * <p><b>稳定度</b>：{@link #stabilityOf} 把样本数折算为观测时长，约 10 小时观测后收敛到 1.0
 * （§6.2 验收「玩家画像在 10 小时游戏后趋于稳定」）。</p>
 *
 * <p><b>画像应用（§6.2）</b>：{@link #adaptiveThresholdMultiplier} 按观测时长与误报历史给出阈值倍率
 * ——新玩家更敏感（×{@value #NEW_PLAYER_MULTIPLIER}）、零误报老玩家更宽松（×{@value #VETERAN_MULTIPLIER}）、
 * 硬件指纹突变标记可疑并收紧（×{@value #SUSPICIOUS_MULTIPLIER}）；行为模式突变则调用既有
 * {@link ZeroDayAnomalyService} 做分布外核查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorProfileService {

    /** 单条观测代表的游戏时长（秒）：客户端按固定节奏上报特征向量，故样本数可折算为观测时长。 */
    public static final int OBSERVATION_SECONDS = 30;
    /** 画像趋于稳定的观测时长：10 小时（§6.2 验收）。 */
    public static final int STABLE_SECONDS = 10 * 3600;
    /** 新玩家门槛：观测不足 10 小时判定为「画像未成型」。 */
    public static final int NEW_PLAYER_SECONDS = 10 * 3600;
    /** 老玩家门槛：观测超过 100 小时。 */
    public static final int VETERAN_SECONDS = 100 * 3600;

    /** 新玩家阈值倍率：更敏感。 */
    public static final double NEW_PLAYER_MULTIPLIER = 0.85;
    /** 零误报老玩家阈值倍率：更宽松。 */
    public static final double VETERAN_MULTIPLIER = 1.15;
    /** 已建立画像的常规倍率。 */
    public static final double NORMAL_MULTIPLIER = 1.0;
    /** 设备指纹突变（多指纹）时的收紧倍率。 */
    public static final double SUSPICIOUS_MULTIPLIER = 0.9;

    /** 行为突变判定：任一跟踪特征偏离个人基线超过该 z-score 即视为行为模式突变。 */
    public static final double PATTERN_MUTATION_Z = 3.5;
    /** 行为突变判定所需最小样本数：画像未成型前不判定，避免冷启动误报。 */
    public static final int PATTERN_MUTATION_MIN_SAMPLES = 60;

    /** 作息明细保留天数，夜间任务压缩更早的历史。 */
    public static final int DAILY_PATTERN_RETENTION_DAYS = 180;

    /** 跟踪特征：点击速度。 */
    private static final String F_CPS = "feature_click_cps";
    /** 跟踪特征：瞄准平滑度。 */
    private static final String F_AIM = "feature_aim_smoothness";
    /** 跟踪特征：移动速度比。 */
    private static final String F_SPEED = "feature_speed_ratio";

    private final PlayerBehaviorProfileRepository profileRepository;
    private final PlayerDailyPatternRepository dailyPatternRepository;
    private final PlayerHardwareFingerprintRepository hardwareFingerprintRepository;
    private final ZeroDayAnomalyService zeroDayAnomalyService;

    /**
     * 折叠一条观测：更新在线统计、稳定度、作息明细；行为模式突变时触发零日核查。
     *
     * @param pteid        玩家
     * @param fv           本次观测的特征向量（仅跟踪其中存在的跟踪特征）
     * @param sessionStart 是否为一次会话的起点（起点则会话数 +1）
     */
    @Transactional
    public void observe(String pteid, FeatureVector fv, boolean sessionStart) {
        if (pteid == null || pteid.isBlank() || fv == null || fv.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        PlayerBehaviorProfile p = profileRepository.findById(pteid)
                .orElseGet(() -> newProfile(pteid, now));
        Map<String, Double> values = fv.asMap();
        boolean mutation = isPatternMutation(p, values);

        long n = p.getSampleCount();
        if (values.containsKey(F_CPS)) {
            double[] s = welford(p.getMeanCps(), p.getCpsStd(), n, values.get(F_CPS));
            p.setMeanCps(s[0]);
            p.setCpsStd(s[1]);
        }
        if (values.containsKey(F_AIM)) {
            double[] s = welford(p.getMeanAimSmoothness(), p.getAimSmoothnessStd(), n, values.get(F_AIM));
            p.setMeanAimSmoothness(s[0]);
            p.setAimSmoothnessStd(s[1]);
        }
        if (values.containsKey(F_SPEED)) {
            double[] s = welford(p.getMeanSpeed(), p.getSpeedStd(), n, values.get(F_SPEED));
            p.setMeanSpeed(s[0]);
            p.setSpeedStd(s[1]);
        }

        p.setSampleCount(n + 1);
        if (sessionStart) {
            p.setTotalSessions(p.getTotalSessions() + 1);
        }
        p.setStability(stabilityOf(p.getSampleCount()));
        p.setProfileVersion(PlayerBehaviorProfile.CURRENT_PROFILE_VERSION);
        p.setUpdatedAt(now);
        profileRepository.save(p);

        ZonedDateTime zdt = now.atZone(ZoneId.systemDefault());
        accumulateDailyPattern(pteid, zdt.toLocalDate(), zdt.getHour());

        if (mutation) {
            // 行为模式突变：交由既有零日引擎做分布外核查（不修改该服务，仅调用其公开 API）
            Map<String, Object> finding = zeroDayAnomalyService.assess(pteid, "UNKNOWN", fv);
            log.info("行为模式突变触发零日核查 pteid={} finding={}", pteid, finding.get("finding_id"));
        }
    }

    /**
     * 自适应阈值倍率（§6.2 画像应用）：
     * <ul>
     *   <li>新玩家（&lt;10 小时）或无画像：×{@value #NEW_PLAYER_MULTIPLIER}（更敏感）；</li>
     *   <li>老玩家（&gt;100 小时且零误报）：×{@value #VETERAN_MULTIPLIER}（更宽松）；</li>
     *   <li>其余已建立画像：×{@value #NORMAL_MULTIPLIER}；</li>
     *   <li>硬件指纹突变（过手多枚指纹）：判定可疑，额外收紧到不超过 ×{@value #SUSPICIOUS_MULTIPLIER}。</li>
     * </ul>
     */
    public double adaptiveThresholdMultiplier(String pteid) {
        PlayerBehaviorProfile p = (pteid == null || pteid.isBlank())
                ? null : profileRepository.findById(pteid).orElse(null);
        double base;
        if (p == null || observedSeconds(p) < NEW_PLAYER_SECONDS) {
            base = NEW_PLAYER_MULTIPLIER;
        } else if (observedSeconds(p) > VETERAN_SECONDS && p.getFalsePositives() == 0) {
            base = VETERAN_MULTIPLIER;
        } else {
            base = NORMAL_MULTIPLIER;
        }
        if (hasDeviceMutation(pteid)) {
            base = Math.min(base, SUSPICIOUS_MULTIPLIER);
        }
        return base;
    }

    /** 画像视图（含稳定度、观测时长与当前自适应倍率）；无画像行时如实返回 exists=false。 */
    public Map<String, Object> profile(String pteid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid);
        PlayerBehaviorProfile p = (pteid == null || pteid.isBlank())
                ? null : profileRepository.findById(pteid).orElse(null);
        if (p == null) {
            out.put("exists", false);
            out.put("sample_count", 0L);
            out.put("stability", 0.0);
            out.put("observed_seconds", 0L);
            out.put("adaptive_multiplier", NEW_PLAYER_MULTIPLIER);
            out.put("fingerprint_mutation", false);
            return out;
        }
        out.put("exists", true);
        out.put("mean_cps", p.getMeanCps());
        out.put("cps_std", p.getCpsStd());
        out.put("mean_aim_smoothness", p.getMeanAimSmoothness());
        out.put("aim_smoothness_std", p.getAimSmoothnessStd());
        out.put("mean_speed", p.getMeanSpeed());
        out.put("speed_std", p.getSpeedStd());
        out.put("total_sessions", p.getTotalSessions());
        out.put("total_detections", p.getTotalDetections());
        out.put("false_positives", p.getFalsePositives());
        out.put("reputation_score", p.getReputationScore());
        out.put("reputation_level", p.getReputationLevel());
        out.put("profile_version", p.getProfileVersion());
        out.put("sample_count", p.getSampleCount());
        out.put("stability", p.getStability());
        out.put("observed_seconds", observedSeconds(p));
        out.put("adaptive_multiplier", adaptiveThresholdMultiplier(pteid));
        out.put("fingerprint_mutation", hasDeviceMutation(pteid));
        out.put("first_seen_at", p.getFirstSeenAt() == null ? "" : p.getFirstSeenAt().toString());
        out.put("updated_at", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
        return out;
    }

    /** 风险 / 高危玩家（分值 &lt; 500），按分值升序取前 limit 个。 */
    public List<Map<String, Object>> highRisk(int limit) {
        int n = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayerBehaviorProfile p : profileRepository.findByReputationScoreLessThanEqualOrderByReputationScoreAsc(
                ReputationV2Service.LEVEL_OBSERVED_MIN - 1)) {
            if (out.size() >= n) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pteid", p.getPteid());
            m.put("reputation_score", p.getReputationScore());
            m.put("reputation_level", p.getReputationLevel());
            m.put("false_positives", p.getFalsePositives());
            m.put("updated_at", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 记录一次检测命中：确认命中计入 totalDetections，误报另计 falsePositives。 */
    @Transactional
    public boolean recordObservationOfDetection(String pteid, boolean confirmed) {
        if (pteid == null || pteid.isBlank()) {
            return false;
        }
        PlayerBehaviorProfile p = profileRepository.findById(pteid).orElse(null);
        if (p == null) {
            return false;
        }
        p.setTotalDetections(p.getTotalDetections() + 1);
        if (!confirmed) {
            p.setFalsePositives(p.getFalsePositives() + 1);
        }
        p.setUpdatedAt(Instant.now());
        profileRepository.save(p);
        return true;
    }

    /**
     * 记录一次硬件指纹出现。
     *
     * @return 该指纹对此玩家是否为首次出现（true = 新设备，可疑）
     */
    @Transactional
    public boolean deviceFingerprintSeen(String pteid, String fingerprintHash) {
        if (pteid == null || pteid.isBlank() || fingerprintHash == null || fingerprintHash.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        PlayerHardwareFingerprint existing = hardwareFingerprintRepository
                .findByPteidAndFingerprintHash(pteid, fingerprintHash)
                .orElse(null);
        if (existing == null) {
            hardwareFingerprintRepository.save(PlayerHardwareFingerprint.builder()
                    .pteid(pteid)
                    .fingerprintHash(fingerprintHash)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .seenCount(1L)
                    .build());
            return true;
        }
        existing.setLastSeenAt(now);
        existing.setSeenCount(existing.getSeenCount() + 1);
        hardwareFingerprintRepository.save(existing);
        return false;
    }

    /** 该玩家是否过手多枚硬件指纹（指纹突变 → 标记可疑）。 */
    public boolean hasDeviceMutation(String pteid) {
        return pteid != null && !pteid.isBlank() && hardwareFingerprintRepository.countByPteid(pteid) > 1;
    }

    /** 夜间重建（02:30，避开 02:00 的模型训练）：重算稳定度与信誉等级，并压缩过期作息明细。 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void scheduledRebuild() {
        try {
            Map<String, Object> result = rebuildProfiles();
            log.info("画像夜间重建完成 result={}", result);
        } catch (Exception e) {
            // 单轮失败不应中断调度
            log.warn("画像夜间重建失败 err={}", e.getMessage());
        }
    }

    /** 重建全部画像：重算稳定度与信誉等级；清理保留期外的作息明细。空表时安全落空。 */
    @Transactional
    public Map<String, Object> rebuildProfiles() {
        List<PlayerBehaviorProfile> all = profileRepository.findAll();
        int rebuilt = 0;
        Instant now = Instant.now();
        for (PlayerBehaviorProfile p : all) {
            double stability = stabilityOf(p.getSampleCount());
            String level = ReputationV2Service.levelFor(p.getReputationScore());
            if (Double.compare(p.getStability(), stability) != 0 || !level.equals(p.getReputationLevel())) {
                p.setStability(stability);
                p.setReputationLevel(level);
                p.setUpdatedAt(now);
                profileRepository.save(p);
                rebuilt++;
            }
        }
        long compacted = dailyPatternRepository.deleteByPatternDateBefore(
                LocalDate.now().minusDays(DAILY_PATTERN_RETENTION_DAYS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profiles", all.size());
        out.put("rebuilt", rebuilt);
        out.put("daily_patterns_compacted", compacted);
        return out;
    }

    /** 画像稳定度：样本数折算为观测时长，10 小时观测后达到 1.0。 */
    public static double stabilityOf(long sampleCount) {
        if (sampleCount <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, sampleCount * (double) OBSERVATION_SECONDS / STABLE_SECONDS));
    }

    /** 新建画像行（信誉分取 v5.2 初始值）。包级可见，供信誉服务复用，避免重复构造逻辑。 */
    static PlayerBehaviorProfile newProfile(String pteid, Instant now) {
        return PlayerBehaviorProfile.builder()
                .pteid(pteid)
                .reputationScore(ReputationV2Service.INITIAL_SCORE)
                .reputationLevel(ReputationV2Service.levelFor(ReputationV2Service.INITIAL_SCORE))
                .profileVersion(PlayerBehaviorProfile.CURRENT_PROFILE_VERSION)
                .firstSeenAt(now)
                .updatedAt(now)
                .build();
    }

    // ------------------------------ 内部实现 ------------------------------

    /**
     * Welford 在线递推一步：返回 {@code [新均值, 新总体标准差]}。
     * 由写库的总体标准差还原 M2（{@code M2 = n·σ²}），故无需另存 M2 列。
     */
    private static double[] welford(double mean, double std, long n, double x) {
        long n1 = n + 1;
        double delta = x - mean;
        double mean1 = mean + delta / n1;
        double m2 = std * std * n;
        double m2n = m2 + delta * (x - mean1);
        double var1 = m2n / n1;
        return new double[]{mean1, Math.sqrt(Math.max(0.0, var1))};
    }

    /** 任一跟踪特征偏离个人基线超过 {@value #PATTERN_MUTATION_Z} 个标准差即判为行为模式突变。 */
    private static boolean isPatternMutation(PlayerBehaviorProfile p, Map<String, Double> values) {
        if (p.getSampleCount() < PATTERN_MUTATION_MIN_SAMPLES) {
            return false;
        }
        return exceedsBaseline(p.getMeanCps(), p.getCpsStd(), values, F_CPS)
                || exceedsBaseline(p.getMeanAimSmoothness(), p.getAimSmoothnessStd(), values, F_AIM)
                || exceedsBaseline(p.getMeanSpeed(), p.getSpeedStd(), values, F_SPEED);
    }

    private static boolean exceedsBaseline(double mean, double std, Map<String, Double> values, String key) {
        Double x = values.get(key);
        if (x == null || std <= 1e-9) {
            return false;
        }
        return Math.abs((x - mean) / std) > PATTERN_MUTATION_Z;
    }

    /** 增量累加当前「日 + 小时」的作息明细。 */
    private void accumulateDailyPattern(String pteid, LocalDate date, int hour) {
        PlayerDailyPattern row = dailyPatternRepository
                .findByPteidAndPatternDateAndHourOfDay(pteid, date, hour)
                .orElseGet(() -> PlayerDailyPattern.builder()
                        .pteid(pteid)
                        .patternDate(date)
                        .hourOfDay(hour)
                        .build());
        row.setSessionSeconds(row.getSessionSeconds() + OBSERVATION_SECONDS);
        row.setEventCount(row.getEventCount() + 1);
        dailyPatternRepository.save(row);
    }

    /** 已观测时长（秒）= 样本数 × 单条观测代表的时长。 */
    private static long observedSeconds(PlayerBehaviorProfile p) {
        return p.getSampleCount() * OBSERVATION_SECONDS;
    }
}