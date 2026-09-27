package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.AppealService;
import com.potatotv.pacc.service.ConfidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * v5.2 §7.4 申诉自动复核：提交后的申诉先过一遍机器复核，把「明显误报」和「明显违规」直接分流，
 * 中间地带才交给人工。
 *
 * <pre>
 * 复评分 = 加权平均（权重按可得项归一）
 *   原判证据 0.45 + ML 侧零日信号 0.25 + 信誉风险 0.20 + 历史误报率修正 0.10
 *   ≤ {@value #MISREPORT_MAX}  → MISREPORT：自动撤销（作弊记录撤销 + 信誉 +20 + 通知玩家）
 *   ≥ {@value #CONFIRM_MIN}    → CONFIRMED：升到分析师人工复核
 *   其余                        → INCONCLUSIVE：回落客服人工队列
 * </pre>
 *
 * <p><b>为什么是这四个量</b>：红屏事件只携带类型与风险分，不携带原始特征向量，因此「用模型重新推理一遍」
 * 在这个层级没有输入可喂（不伪造一个看起来像 AI 的分数）。这四项都是真实可得且可解释的：
 * 原判风险分来自端侧融合判定、零日信号来自孤立森林 + 自编码器（{@code ZeroDayAnomalyService}）、
 * 信誉来自 {@link ReputationV2Service}、历史误报率来自行为画像。人工复核结论会同时回流成训练样本，
 * 闭环回到 {@link ModelTrainingService}。</p>
 *
 * <p>幂等与去重：只处理 {@code autoReview=PENDING} 的申诉，写完结论后不会重复处理；
 * 扫描每 2 分钟一次，深夜提交的申诉也会在分钟级出结论（远低于 24 小时 SLA）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealAutoReviewService {

    /** 复评分 ≤ 该值：判定误报（自动撤销）。 */
    public static final int MISREPORT_MAX = 30;
    /** 复评分 ≥ 该值：判定维持原判（转人工）。 */
    public static final int CONFIRM_MIN = 70;

    /** 零日信号回看窗口（天）。 */
    static final int SIGNAL_WINDOW_DAYS = 7;
    /** 无信号时的中性取值：不因为「查不到」把分数推向任何一侧。 */
    static final int NEUTRAL = 50;

    /** 权重合计 1.0。 */
    private static final double W_EVIDENCE = 0.45;
    private static final double W_ZERO_DAY = 0.25;
    private static final double W_REPUTATION = 0.20;
    private static final double W_FALSE_POSITIVE = 0.10;

    /** 复评分上限（写入申诉的 prescreenScore 列，保持 0-100）。 */
    private static final int SCORE_MAX = 100;

    private final AppealRepository appeals;
    private final AppealService appealService;
    private final CheatRecordRepository cheatRecords;
    private final ZeroDayFindingRepository zeroDayFindings;
    private final PlayerBehaviorProfileRepository profiles;
    private final ReputationV2Service reputationV2;
    private final ConfidenceService confidenceService;

    /** 复核结论。 */
    public record Outcome(String verdict, int score, String comment, String recycledFindingId) {
    }

    /** 复评明细（可解释性：四项分量 + 合成分 + 结论）。 */
    record Assessment(int evidence, int zeroDay, int reputationRisk, int falsePositiveTerm,
                      int score, String verdict, String comment) {
    }

    /** 周期扫描：每 2 分钟处理一批待复核申诉（首次延迟 30 秒）。 */
    @Scheduled(initialDelay = 30_000L, fixedDelay = 120_000L)
    public void sweep() {
        try {
            Map<String, Object> stats = reviewPending();
            if (!Integer.valueOf(0).equals(stats.get("reviewed"))) {
                log.info("申诉自动复核完成 stats={}", stats);
            }
        } catch (Exception e) {
            log.warn("申诉自动复核扫描失败 err={}", e.getMessage());
        }
    }

    /** 复核全部待处理申诉，返回按结论分类的统计。 */
    @Transactional
    public Map<String, Object> reviewPending() {
        List<Appeal> pending = appeals.findByStatusAndAutoReviewOrderByCreatedAtAsc("pending", "PENDING");
        int misreport = 0;
        int confirmed = 0;
        int inconclusive = 0;
        for (Appeal a : pending) {
            Outcome o = review(a);
            if (o == null) continue;
            switch (o.verdict()) {
                case "MISREPORT" -> misreport++;
                case "CONFIRMED" -> confirmed++;
                default -> inconclusive++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("reviewed", pending.size());
        stats.put("misreport", misreport);
        stats.put("confirmed", confirmed);
        stats.put("inconclusive", inconclusive);
        return stats;
    }

    /**
     * 复核单条申诉：算复评分 → 写结论 → 副作用（信誉 / 画像 / 样本回流）。
     *
     * @return 结论；申诉已被复核过或因状态不符跳过时返回 {@code null}
     */
    public Outcome review(Appeal a) {
        if (a == null || !"pending".equals(a.getStatus()) || !"PENDING".equals(a.getAutoReview())) return null;
        String pteid = a.getPteid();

        Assessment assessment = assess(a);
        Appeal updated = appealService.applyAutoReview(a.getAppealId(), assessment.verdict(),
                assessment.score(), assessment.comment());
        if (updated == null) return null;

        // 复核结论回流为训练样本（§6.1 训练流水线消费：REVIEWED + confirmed + features）
        String recycled = recycleSample(pteid, assessment.verdict(), assessment.score(), assessment.comment());
        if ("MISREPORT".equals(assessment.verdict())) {
            // 申诉成功（误报）加分：以申诉 id 作为来源事件，重复提交不重复加分
            reputationV2.apply(pteid, ReputationV2Service.Event.APPEAL_APPROVED,
                    a.getAppealId(), "AI 复核确认误报");
        }
        log.info("申诉自动复核 appeal={} pteid={} verdict={} score={} 回流样本={}",
                a.getAppealId(), pteid, assessment.verdict(), assessment.score(),
                recycled == null ? "无" : recycled);
        return new Outcome(assessment.verdict(), assessment.score(), assessment.comment(), recycled);
    }

    /**
     * 纯计算复评：四项分量 → 合成分 → 结论与说明。无副作用，便于回归与人工核对。
     *
     * <p>读不到的分量**弃权**（不参与加权、权重按剩余项归一），而不是塞一个 50 分的中性值——
     * 否则「查不到」会被当成「中等可疑」，把本该判误报的申诉推回人工队列。四项全缺时取中性分，
     * 自然落到证据不足。</p>
     */
    Assessment assess(Appeal a) {
        Integer evidence = evidenceStrength(a);
        Integer zeroDay = zeroDaySignal(a.getPteid());
        Integer reputationRisk = reputationRisk(a.getPteid());
        Integer falsePositiveTerm = falsePositiveTerm(a.getPteid());

        double weighted = 0;
        double weight = 0;
        if (evidence != null) {
            weighted += W_EVIDENCE * evidence;
            weight += W_EVIDENCE;
        }
        if (zeroDay != null) {
            weighted += W_ZERO_DAY * zeroDay;
            weight += W_ZERO_DAY;
        }
        if (reputationRisk != null) {
            weighted += W_REPUTATION * reputationRisk;
            weight += W_REPUTATION;
        }
        if (falsePositiveTerm != null) {
            weighted += W_FALSE_POSITIVE * falsePositiveTerm;
            weight += W_FALSE_POSITIVE;
        }
        int score = weight <= 0 ? NEUTRAL : (int) Math.round(weighted / weight);
        score = Math.max(0, Math.min(SCORE_MAX, score));

        String verdict = score <= MISREPORT_MAX ? "MISREPORT"
                : score >= CONFIRM_MIN ? "CONFIRMED" : "INCONCLUSIVE";
        String comment = String.format(Locale.ROOT,
                "AI 自动复核：复评分 %d（原判证据 %s / 零日信号 %s / 信誉风险 %s / 历史误报修正 %s）→ %s",
                score, orDash(evidence), orDash(zeroDay), orDash(reputationRisk), orDash(falsePositiveTerm),
                verdictLabel(verdict));
        return new Assessment(orZero(evidence), orZero(zeroDay), orZero(reputationRisk),
                orZero(falsePositiveTerm), score, verdict, comment);
    }

    /** 分量缺失时在说明里标注「无数据」，而不是显示一个不存在的 0。 */
    private static String orDash(Integer v) {
        return v == null ? "无数据" : String.valueOf(v);
    }

    private static int orZero(Integer v) {
        return v == null ? -1 : v;
    }

    // ------------------------------ 复评分构成 ------------------------------

    /** 原判证据强度（0-100）：以关联作弊记录的风险分为准；无记录时无数据（弃权）。 */
    private Integer evidenceStrength(Appeal a) {
        if (a.getAlertId() == null || a.getAlertId().isBlank()) return null;
        CheatRecord record = cheatRecords.findFirstByAlertId(a.getAlertId()).orElse(null);
        if (record == null) return null;
        int score = Math.max(0, Math.min(100, record.getRiskScore()));
        if (record.isRevoked()) score = Math.min(score, MISREPORT_MAX); // 已被撤销的记录不再作为维持依据
        return score;
    }

    /**
     * ML 侧零日信号（0-100）：窗口内最高综合分；人工已确认为真样本 → 至少 90，
     * 已确认为误报 → 最多 20；窗口内没有记录时无数据（弃权）。零日检测本身是孤立森林 + 自编码器输出，
     * 与红屏判定相互独立。
     */
    private Integer zeroDaySignal(String pteid) {
        List<ZeroDayFinding> rows = recentFindings(pteid);
        if (rows.isEmpty()) return null;
        int max = 0;
        boolean confirmedCheat = false;
        boolean confirmedMisreport = false;
        for (ZeroDayFinding f : rows) {
            max = Math.max(max, f.getCompositeScore());
            if (Boolean.TRUE.equals(f.getConfirmed())) confirmedCheat = true;
            if (Boolean.FALSE.equals(f.getConfirmed())) confirmedMisreport = true;
        }
        if (confirmedCheat) return Math.max(max, 90);
        if (confirmedMisreport) return Math.min(max, 20);
        return max;
    }

    /** 信誉风险（0-100）：信誉分越高风险越低（1000 分 → 0，0 分 → 100）。 */
    private Integer reputationRisk(String pteid) {
        int score = reputationV2.score(pteid);
        return Math.max(0, Math.min(100, 100 - score / 10));
    }

    /** 历史误报率修正（0-100）：误报占比越高，复评分越低（50% 误报率即归零）；无画像时无数据。 */
    private Integer falsePositiveTerm(String pteid) {
        PlayerBehaviorProfile profile = profiles.findById(pteid).orElse(null);
        if (profile == null || profile.getTotalDetections() <= 0) return null;
        double ratio = (double) profile.getFalsePositives() / profile.getTotalDetections();
        return (int) Math.round(Math.max(0, 100 - Math.min(100, ratio * 200)));
    }

    /** 窗口内的零日发现（玩家为空时返回空表）。 */
    private List<ZeroDayFinding> recentFindings(String pteid) {
        if (pteid == null || pteid.isBlank()) return List.of();
        Instant since = Instant.now().minus(SIGNAL_WINDOW_DAYS, ChronoUnit.DAYS);
        return zeroDayFindings.findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(pteid, since);
    }

    // ------------------------------ 样本回流 ------------------------------

    /**
     * 写入一条已复核零日发现作为训练样本：{@code MISREPORT} 记 confirmed=false，
     * 其余记 confirmed=true，特征沿用窗口内最近一条（无特征时不写，避免污染训练集）。
     */
    private String recycleSample(String pteid, String verdict, int score, String comment) {
        List<ZeroDayFinding> rows = recentFindings(pteid);
        if (rows.isEmpty()) return null;
        String features = rows.stream()
                .map(ZeroDayFinding::getFeaturesJson)
                .filter(f -> f != null && !f.isBlank())
                .findFirst()
                .orElse(null);
        if (features == null) return null;
        ZeroDayFinding sample = ZeroDayFinding.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .pteid(pteid)
                .edition(rows.get(0).getEdition())
                .compositeScore(score)
                .confidenceTier(confidenceService.classify(score).name())
                .status(ZeroDayFinding.Status.REVIEWED)
                .featuresJson(features)
                .reviewer("ai-appeal")
                .reviewComment(comment)
                .reviewedAt(Instant.now())
                .confirmed(!"MISREPORT".equals(verdict))
                .build();
        zeroDayFindings.save(sample);
        return sample.getId();
    }

    private static String verdictLabel(String verdict) {
        return switch (verdict) {
            case "MISREPORT" -> "误报（自动撤销）";
            case "CONFIRMED" -> "维持原判（转分析师）";
            default -> "证据不足（转客服）";
        };
    }
}