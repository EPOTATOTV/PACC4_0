package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ConfidenceTier;
import com.potatotv.pacc.domain.SuspicionFlag;
import com.potatotv.pacc.repository.SuspicionFlagRepository;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareReport;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareVerdict;
import com.potatotv.pacc.service.IntegrityGuardService.IntegrityReport;
import com.potatotv.pacc.service.IntegrityGuardService.State;
import com.potatotv.pacc.util.InputTimingAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * v4.5 对抗风险融合编排。
 * <p>把硬件指纹、输入时序宏、完整性三路判定融合为单一对抗风险分，并复用 v4.4 的
 * 三级置信度决策：</p>
 * <ul>
 *   <li>HIGH → 红屏（经 {@link RedscreenService#decideAndHandle}）</li>
 *   <li>MEDIUM / 完整性 SUSPECT → 写 {@code SuspicionFlag} 深观察（HARDWARE_CHEAT / TAMPERED_INTEGRITY）</li>
 *   <li>LOW → 仅日志</li>
 * </ul>
 * <p>完整性 {@code TAMPERED} 视为对抗级破坏，强制走红屏而不受置信度门限。</p>
 * <p>纯计算入口 {@link #fuse(HardwareVerdict, InputTimingAnalyzer.MacroVerdict, IntegrityReport)}
 * 便于确定性单测；{@link #handle(...)} 负责落库决策。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterMeasureRiskService {

    private final HardwareFingerprintService hardwareFingerprintService;
    private final IntegrityGuardService integrityGuard;
    private final ConfidenceService confidenceService;
    private final RedscreenService redscreenService;
    private final SuspicionFlagRepository suspicionFlagRepository;

    // 融合权重：硬件 45% + 宏 25% + 完整性 30%
    private static final double W_HW = 0.45, W_MACRO = 0.25, W_INT = 0.30;

    @Value("${pacc.countermeasure.input-macro-cv:0.06}")
    private double macroCvThreshold = 0.06;

    public record Components(int hardwareScore, int macroScore, int integrityScore, State integrityState) {
    }

    public record CounterMeasureResult(int riskScore, ConfidenceTier tier, Components components,
                                       boolean forcedRedscreen, String cheatType) {
    }

    /** 纯融合：三路得分 → 融合分 + 置信度等级（不做任何落库/副作用）。 */
    public CounterMeasureResult fuse(HardwareVerdict hw, InputTimingAnalyzer.MacroVerdict macro,
                                     IntegrityReport integrity) {
        int hwScore = hw != null ? hw.score() : 0;
        int macroScore = macro != null ? macro.score() : 0;
        int intScore = integrity != null ? integrity.score() : 0;
        State intState = integrity != null ? integrity.state() : State.CLEAN;

        double fused = hwScore * W_HW + macroScore * W_MACRO + intScore * W_INT;
        int score = (int) Math.round(fused);

        // 完整性 TAMERED 视为对抗级破坏：强制红屏，不受置信度门限
        boolean forced = intState == State.TAMPERED;
        if (forced) score = Math.max(score, confidenceService.highThreshold());

        ConfidenceTier tier = confidenceService.classify(score);
        String cheatType = forced ? "INTEGRITY_TAMPERED"
                : (hw != null && hw.matched() ? "HARDWARE_CHEAT" : "INPUT_MACRO");
        Components comps = new Components(hwScore, macroScore, intScore, intState);
        return new CounterMeasureResult(score, tier, comps, forced, cheatType);
    }

    /**
     * 编排入口：融合 + 依据等级落库决策。返回融合结果（含 tier 与是否强制红屏）。
     */
    public CounterMeasureResult handle(String pteid, String edition, List<HardwareReport> reports,
                                       List<Double> intervalsMs,
                                       IntegrityGuardService.Input integrityInput) {
        HardwareVerdict hw = hardwareFingerprintService.scan(reports);
        InputTimingAnalyzer.MacroVerdict macro = InputTimingAnalyzer.analyze(intervalsMs, macroCvThreshold);
        IntegrityReport integrity = integrityGuard.assess(integrityInput);

        CounterMeasureResult r = fuse(hw, macro, integrity);
        String detail = String.format("hw=%d macro=%d integrity=%d(%s)",
                r.components().hardwareScore(), r.components().macroScore(),
                r.components().integrityScore(), r.components().integrityState());

        if (r.forcedRedscreen() || r.tier() == ConfidenceTier.HIGH) {
            redscreenService.decideAndHandle(pteid, r.cheatType(), r.riskScore(), edition, detail);
        } else if (r.tier() == ConfidenceTier.MEDIUM) {
            // 深观察：硬件疑似 vs 完整性可疑，分别标记
            State st = r.components().integrityState();
            SuspicionFlag.Kind kind = (st != null && st != State.CLEAN)
                    ? SuspicionFlag.Kind.TAMPERED_INTEGRITY : SuspicionFlag.Kind.HARDWARE_CHEAT;
            suspicionFlagRepository.save(SuspicionFlag.builder()
                    .flagId("flag_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 4))
                    .pteid(pteid)
                    .kind(kind)
                    .weight(r.riskScore())
                    .status(SuspicionFlag.Status.OPEN)
                    .detail(r.cheatType() + " @ " + edition)
                    .evidenceSummary(detail)
                    .build());
            log.info("对抗中置信深观察 pteid={} kind={} score={}", pteid, kind, r.riskScore());
        } else {
            log.debug("对抗低置信（仅日志） pteid={} score={}", pteid, r.riskScore());
        }
        return r;
    }

    public double macroCvThreshold() {
        return macroCvThreshold;
    }
}