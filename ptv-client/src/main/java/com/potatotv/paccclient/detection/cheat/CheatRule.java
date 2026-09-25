package com.potatotv.paccclient.detection.cheat;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;

import java.util.Optional;

/**
 * 单条作弊判定规则（文档 §2.3.1 的 L0 规则层）。
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>只读入参，不做副作用、不抛异常；</li>
 *   <li>证据不足时返回 {@link Optional#empty()}，不得用「缺失即 0」判为违规；</li>
 *   <li>分值语义为 0-100 置信分，达 {@link CheatFinding#REPORT_THRESHOLD} 才视为命中。</li>
 * </ul>
 */
public interface CheatRule {

    /** 规则对应的作弊类型。 */
    CheatType type();

    /**
     * 评估一次。
     *
     * @param fv  当前特征向量
     * @param ctx 行为分析上下文（点击分布 / 轨迹 / 时序异常 / AI 分）
     * @return 命中结果；未达阈值或无证据时为空
     */
    Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx);
}
