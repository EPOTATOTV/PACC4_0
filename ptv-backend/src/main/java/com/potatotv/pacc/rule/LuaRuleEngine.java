package com.potatotv.pacc.rule;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua 动态规则引擎。
 *
 * <p>从 classpath <code>rules/*.lua</code> 加载检测规则，每条规则导出一个表：</p>
 * <pre>
 * return {
 *   id = "memory_tamper", name = "内存篡改", enabled = true,
 *   evaluate = function(ctx)
 *     if ctx.event_type == "memory_tamper" then
 *       return { hit = true, score = 10, reason = "内存区域被篡改" }
 *     end
 *     return { hit = false }
 *   end
 * }
 * </pre>
 *
 * <p>规则可经 {@link #reload()} 热更新；命中分值在 {@link #maxBonus} 上限内累加，
 * 由风险评分服务合并进综合分。任何单条规则异常都不会影响主流程。</p>
 */
@Service
public class LuaRuleEngine {

    /** 已加载的规则（volatile 保证热更新可见性）。 */
    private volatile List<LuaRule> rules = List.of();

    private final boolean enabled;
    private final double maxBonus;

    public LuaRuleEngine(@Value("${pacc.rules.enabled:true}") boolean enabled,
                         @Value("${pacc.rules.max-bonus:15}") double maxBonus) {
        this.enabled = enabled;
        this.maxBonus = maxBonus;
        if (enabled) {
            reload();
        }
    }

    /** 单条已编译的 Lua 规则。 */
    public static class LuaRule {
        public final String fileName;
        public final String id;
        public final String name;
        public final boolean ruleEnabled;
        public final Globals globals;
        public final LuaValue evaluate;

        LuaRule(String fileName, String id, String name, boolean ruleEnabled,
                Globals globals, LuaValue evaluate) {
            this.fileName = fileName;
            this.id = id;
            this.name = name;
            this.ruleEnabled = ruleEnabled;
            this.globals = globals;
            this.evaluate = evaluate;
        }
    }

    /** 单条规则命中结果。 */
    public record RuleHit(String id, String name, double score, String reason) {
    }

    /** 整体评估结果。 */
    public record Evaluation(List<RuleHit> hits, double bonus, int ruleCount) {
    }

    /**
     * 重新加载全部规则（热更新）。返回加载成功的规则数。
     */
    public synchronized int reload() {
        List<LuaRule> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:rules/*.lua");
            for (Resource r : resources) {
                try {
                    LuaRule rule = compile(r);
                    if (rule != null) {
                        loaded.add(rule);
                    }
                } catch (Exception e) {
                    // 单条规则加载失败不阻断整体
                    System.err.println("[LuaRuleEngine] 规则加载失败 " + r.getFilename() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[LuaRuleEngine] 扫描规则目录失败: " + e.getMessage());
        }
        this.rules = List.copyOf(loaded);
        return loaded.size();
    }

    private LuaRule compile(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Globals globals = JsePlatform.standardGlobals();
            LuaValue chunk = globals.load(script, r.getFilename());
            LuaValue desc = chunk.call();

            String id = desc.get("id").optjstring(r.getFilename());
            String name = desc.get("name").optjstring(id);
            boolean ruleEnabled = desc.get("enabled").optboolean(true);
            LuaValue evaluate = desc.get("evaluate");
            if (!evaluate.isfunction()) {
                throw new IllegalArgumentException("规则缺少 evaluate 函数: " + r.getFilename());
            }
            return new LuaRule(r.getFilename(), id, name, ruleEnabled, globals, evaluate);
        }
    }

    /**
     * 对事件上下文评估全部启用规则，返回命中列表与累计加分（受 {@code maxBonus} 封顶）。
     */
    public Evaluation evaluate(Map<String, Object> ctx) {
        List<RuleHit> hits = new ArrayList<>();
        if (!enabled) {
            return new Evaluation(hits, 0.0, 0);
        }
        double total = 0.0;
        int count = 0;
        for (LuaRule rule : rules) {
            if (!rule.ruleEnabled) {
                continue;
            }
            count++;
            try {
                LuaValue result = rule.evaluate.call(toLuaTable(ctx));
                if (result.isnil()) {
                    continue;
                }
                LuaTable t = result.checktable();
                if (t.get("hit").optboolean(false)) {
                    double score = t.get("score").optdouble(0.0);
                    String reason = t.get("reason").optjstring("");
                    total += score;
                    hits.add(new RuleHit(rule.id, rule.name, score, reason));
                }
            } catch (Exception e) {
                // 规则异常不影响主流程
                System.err.println("[LuaRuleEngine] 规则执行异常 " + rule.id + ": " + e.getMessage());
            }
        }
        double bonus = Math.min(total, maxBonus);
        return new Evaluation(hits, bonus, count);
    }

    /** 规则清单（供管理端展示）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LuaRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", r.fileName);
            m.put("id", r.id);
            m.put("name", r.name);
            m.put("enabled", r.ruleEnabled);
            out.add(m);
        }
        return out;
    }

    private static LuaValue toLuaTable(Map<String, Object> ctx) {
        LuaTable t = new LuaTable();
        for (Map.Entry<String, Object> e : ctx.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                t.set(e.getKey(), LuaValue.NIL);
            } else if (v instanceof Number n) {
                t.set(e.getKey(), LuaValue.valueOf(n.doubleValue()));
            } else if (v instanceof Boolean b) {
                t.set(e.getKey(), LuaValue.valueOf(b.booleanValue()));
            } else {
                t.set(e.getKey(), LuaValue.valueOf(String.valueOf(v)));
            }
        }
        return t;
    }
}
