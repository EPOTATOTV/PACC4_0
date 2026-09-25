package com.potatotv.paccclient.detection.cheat;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.rules.AutoArmorRule;
import com.potatotv.paccclient.detection.cheat.rules.AutoBlockRule;
import com.potatotv.paccclient.detection.cheat.rules.BlinkRule;
import com.potatotv.paccclient.detection.cheat.rules.ChestStealerRule;
import com.potatotv.paccclient.detection.cheat.rules.CriticalsRule;
import com.potatotv.paccclient.detection.cheat.rules.FastBreakRule;
import com.potatotv.paccclient.detection.cheat.rules.FastEatRule;
import com.potatotv.paccclient.detection.cheat.rules.FastPlaceRule;
import com.potatotv.paccclient.detection.cheat.rules.InvManagerRule;
import com.potatotv.paccclient.detection.cheat.rules.NoFallRule;
import com.potatotv.paccclient.detection.cheat.rules.NukerRule;
import com.potatotv.paccclient.detection.cheat.rules.ScaffoldRule;
import com.potatotv.paccclient.detection.cheat.rules.SprintRule;
import com.potatotv.paccclient.detection.cheat.rules.StepRule;
import com.potatotv.paccclient.detection.cheat.rules.VelocityRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 作弊规则注册表（文档 §3.2）：文档新增的 15 种作弊类型各对应一条独立规则，
 * 一次 {@link #evaluate(FeatureVector, AnalysisContext)} 跑完全部规则并按置信分降序返回命中。
 *
 * <p>规则对象无状态、可复用；注册表本身线程安全（规则列表构造后不可变）。</p>
 */
public final class CheatRuleRegistry {

    private final List<CheatRule> rules;

    /** 注册文档 §3.2 的全部 15 条规则。 */
    public CheatRuleRegistry() {
        this(defaultRules());
    }

    /** 注入自定义规则集（便于单测与灰度裁剪）。 */
    public CheatRuleRegistry(List<CheatRule> rules) {
        this.rules = List.copyOf(rules);
    }

    private static List<CheatRule> defaultRules() {
        return List.of(
                new ScaffoldRule(),
                new FastPlaceRule(),
                new FastBreakRule(),
                new NukerRule(),
                new CriticalsRule(),
                new VelocityRule(),
                new NoFallRule(),
                new StepRule(),
                new SprintRule(),
                new AutoBlockRule(),
                new ChestStealerRule(),
                new InvManagerRule(),
                new AutoArmorRule(),
                new FastEatRule(),
                new BlinkRule());
    }

    /** 已注册规则（只读）。 */
    public List<CheatRule> rules() {
        return rules;
    }

    /** 规则数（文档要求 15）。 */
    public int size() {
        return rules.size();
    }

    /**
     * 跑完全部规则。
     *
     * @param fv  特征向量
     * @param ctx 分析上下文
     * @return 达上报阈值的命中，按置信分降序；无命中返回空列表
     */
    public List<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        List<CheatFinding> hits = new ArrayList<>();
        for (CheatRule rule : rules) {
            rule.evaluate(fv, ctx).filter(CheatFinding::reportable).ifPresent(hits::add);
        }
        hits.sort(Comparator.comparingInt(CheatFinding::score).reversed());
        return hits;
    }

    /** 最高分命中；无命中为空。 */
    public Optional<CheatFinding> evaluateTop(FeatureVector fv, AnalysisContext ctx) {
        List<CheatFinding> hits = evaluate(fv, ctx);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
