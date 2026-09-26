package com.potatotv.prl.engine;

import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.runtime.PrlExecutionObserver;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.vm.PrlVm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则管理器（设计文档 §2.12.2）。
 *
 * <p>持有「规则名 → 运行时状态」的表，装载用原子替换：{@link #loadRule} 编译成功后才 {@code put}，
 * 编译失败直接抛出，表里的旧规则原样保留。这就是 §2.12.1 图里「编译失败 → 保留旧规则」那一支 ——
 * 它之所以成立，是因为替换动作只有一个 {@code ConcurrentHashMap.put}，没有中间的「先删后加」窗口。</p>
 *
 * <p>{@link #executeAll} 按规则名<strong>字典序</strong>执行，而不是遍历哈希表。规则之间没有依赖，
 * 但同一次输入产生的多条告警、以及失败次数这样的累计状态，会以另一种顺序呈现给运维，排查时不好对齐；
 * 固定顺序的代价只是排一次名。</p>
 */
public final class RuleManager {

    /** §2.12.2：累计失败超过这个条数就自动禁用该规则。 */
    public static final int MAX_FAILURES = 10;

    private final Map<String, RuleInstance> rules = new ConcurrentHashMap<>();
    private final PrlCompiler compiler;
    private final PrlVm vm;
    private final PrlHostContext host;

    public RuleManager() {
        this(PrlHostContext.EMPTY);
    }

    public RuleManager(PrlHostContext host) {
        this(host, PrlExecutionObserver.NONE);
    }

    /**
     * @param observer 挂给 {@link PrlVm} 的执行观察者（§2.15.1 调试器、§2.15.2 Profiler）。
     *                 传 {@link PrlExecutionObserver#NONE} 时执行路径上不产生任何额外开销
     */
    public RuleManager(PrlHostContext host, PrlExecutionObserver observer) {
        this.host = host == null ? PrlHostContext.EMPTY : host;
        this.compiler = new PrlCompiler(this.host);
        this.vm = new PrlVm(this.host, observer);
    }

    /** 本管理器使用的宿主上下文；热加载器要用同一份来编译（否则白名单检查与运行期不一致）。 */
    public PrlHostContext host() {
        return host;
    }

    // ------------------------------------------------------------------ 装载

    /**
     * 编译并装载一条规则，同名的旧规则被原子替换。
     *
     * @throws com.potatotv.prl.PrlException 编译失败；此时旧规则不受影响
     */
    public RuleInstance loadRule(String name, String source) {
        return loadBytecode(name, compiler.compile(source));
    }

    /** 直接装载编译产物（版本库里回滚、灰度时用，不必拿源码再编一遍）。 */
    public RuleInstance loadBytecode(String name, PrlBytecode bytecode) {
        RuleInstance instance = new RuleInstance(name, bytecode, System.currentTimeMillis());
        rules.put(name, instance);
        return instance;
    }

    /** 卸载规则；返回是否真的卸掉了。 */
    public boolean unloadRule(String name) {
        return rules.remove(name) != null;
    }

    public void clear() {
        rules.clear();
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行所有已启用的规则，收集告警（§2.12.2 的 {@code executeAll}）。
     *
     * <p>单条规则失败不打断整轮：失败计数累加、超过 {@link #MAX_FAILURES} 自动禁用，其余规则继续跑。
     * 单条规则抛异常就把整批告警丢掉，对宿主来说等于「这批输入白跑」，比让它自己判断严重得多。</p>
     */
    public List<DetectionResult> executeAll(Map<String, Object> input) {
        List<DetectionResult> results = new ArrayList<>();
        for (String name : sortedNames()) {
            RuleInstance rule = rules.get(name);
            if (rule == null) {
                continue;
            }
            DetectionResult result = execute(rule, input);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /** 执行单条规则；未启用、冷却中或没有告警时返回空。 */
    public Optional<DetectionResult> executeRule(String name, Map<String, Object> input) {
        RuleInstance rule = rules.get(name);
        if (rule == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(execute(rule, input));
    }

    private DetectionResult execute(RuleInstance rule, Map<String, Object> input) {
        long now = System.currentTimeMillis();
        if (!rule.isEnabled() || !rule.cooldownOk(now)) {
            return null;
        }
        try {
            Object result = executeBytecode(rule, input);
            rule.markExecuted(now);
            if (result instanceof DetectionResult detection) {
                // emit_alert 不知道自己在哪条规则里（§2.4.7），规则名由引擎补。
                return detection.ruleName() == null || detection.ruleName().isEmpty()
                        ? detection.withRuleName(rule.name())
                        : detection;
            }
            return null;
        } catch (Exception e) {
            if (rule.recordFailure(e) > MAX_FAILURES) {
                rule.disable();
                host.log("error", "规则 " + rule.name() + " 连续失败 " + rule.failureCount()
                        + " 次，已自动禁用：" + e);
            } else {
                host.log("warn", "规则 " + rule.name() + " 执行失败：" + e);
            }
            return null;
        }
    }

    private Object executeBytecode(RuleInstance rule, Map<String, Object> input) {
        PrlBytecode bytecode = rule.bytecode();
        // 一条规则一个 PrlBytecode 时入口函数就是它自己；文件里有多条规则时按名定位，别拿第一条顶替。
        if (bytecode.rule(rule.name()) != null) {
            return vm.executeRule(bytecode, rule.name(), input);
        }
        return vm.execute(bytecode, input);
    }

    // ------------------------------------------------------------------ 查询

    public Optional<RuleInstance> rule(String name) {
        return Optional.ofNullable(rules.get(name));
    }

    /** 已装载的规则名，字典序。 */
    public List<String> ruleNames() {
        return List.copyOf(sortedNames());
    }

    public int size() {
        return rules.size();
    }

    private TreeSet<String> sortedNames() {
        return new TreeSet<>(rules.keySet());
    }
}