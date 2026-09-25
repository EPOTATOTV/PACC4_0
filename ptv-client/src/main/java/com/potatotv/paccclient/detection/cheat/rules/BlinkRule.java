package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Blink（位置闪烁）规则（文档 §3.2）：数据包延迟 + 位置跳变。
 *
 * <p>Blink 通过滞后发包制造位移跳变。低延迟链路（&lt;60ms）下没有理由出现大位移跳变；
 * 若同时伴随高丢包/高延迟，则先按网络抖动解释，扣分后不判定（降误报）。</p>
 */
public final class BlinkRule implements CheatRule {

    /** 位移跳变判定阈值（格）。 */
    private static final double JUMP_THRESHOLD = 5.0;
    /** 低延迟链路阈值（ms）。 */
    private static final double LOW_RTT_MS = 60.0;
    /** 可解释高延迟/丢包的扣分阈值。 */
    private static final double NOISY_PACKET_LOSS = 0.3;

    @Override
    public CheatType type() {
        return CheatType.BLINK;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double teleports = fv.get("feature_teleport_count");
        double jump = fv.get("feature_blink_position_jump");
        if (teleports <= 0 && jump < JUMP_THRESHOLD) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (teleports > 0) {
            score += 40;
            evidence.add("feature_teleport_count");
        }
        if (jump >= JUMP_THRESHOLD) {
            score += 25;
            evidence.add("feature_blink_position_jump");
        }
        double rtt = fv.get("feature_net_rtt_ms");
        if (rtt > 0 && rtt < LOW_RTT_MS) score += 15;
        if (fv.get("feature_net_packet_loss_ratio") > NOISY_PACKET_LOSS) score -= 20;
        if (score < CheatFinding.REPORT_THRESHOLD) return Optional.empty();
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
