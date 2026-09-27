package com.potatotv.pacc.rule;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.PrlAnalyzer;
import com.potatotv.prl.analysis.RuleMetrics;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.bytecode.PrlcFormat;
import com.potatotv.prl.bytecode.RuleEntry;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleInstance;
import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.engine.RuleStatus;
import com.potatotv.prl.engine.RuleVersion;
import com.potatotv.prl.engine.RuleVersionStore;
import com.potatotv.prl.profiler.PrlProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PRL 动态规则引擎，替代原先的 {@code LuaRuleEngine}（设计文档 §3.1.2）。
 *
 * <p>规则从 classpath 的 {@code rules/*.prl} 加载，文件名即规则名。重新加载是原子替换：
 * 单条规则编译失败只丢掉它自己，其余规则与已经装载的旧版本不受影响。</p>
 *
 * <p><b>与规则的数据契约。</b>输入是 {@code RiskScoringService} 构造的事件上下文，
 * 经过 {@link #toRuleInput} 归一成规则 {@code input} 块里声明的强类型字段（PRL 没有隐式
 * 数值转换，宿主必须在边界上把 Integer/字符串统一成 Double/String）。输出取
 * {@code emit_alert} 的返回值：加分点数放 {@code evidence.score}，原因文本放
 * {@code evidence.reason}；{@code score} 缺失时退回 {@code confidence × max-bonus}，
 * 保证规则哪怕只写了可信度也能被计分。</p>
 */
@Service
public class PrlRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(PrlRuleEngine.class);

    private static final String RULE_LOCATION = "classpath*:rules/*.prl";
    private static final String RULE_SUFFIX = ".prl";

    /** emit_alert 证据里承载 PACC 加分点数的键。 */
    private static final String EVIDENCE_SCORE = "score";
    /** emit_alert 证据里承载原因文本的键。 */
    private static final String EVIDENCE_REASON = "reason";

    /** 上下文里的字符串字段 → 规则 input 字段名。 */
    private static final List<TextField> TEXT_FIELDS = List.of(
            new TextField("pteid", "pteid"),
            new TextField("event_type", "event_type"),
            // severity 是 PRL 的关键字（元数据里的 severity:），当变量名会在解析期被拒，规则侧一律叫 severity_level
            new TextField("severity", "severity_level"),
            new TextField("edition", "edition"),
            new TextField("process_name", "process_name"),
            new TextField("memory_region", "memory_region"),
            new TextField("signature_hit", "signature_hit"),
            new TextField("detail", "detail"));

    /**
     * 上下文里的数值字段 → 规则 input 字段名 + 缺省值。
     * 缺省沿用原先 Lua 规则里的 {@code tonumber(x) or default}：client_risk 缺省 0、
     * reputation 缺省 100。规则本身就不用再写兜底，也就不容易和旧行为走偏。
     */
    private static final List<NumericField> NUMERIC_FIELDS = List.of(
            new NumericField("client_risk", "client_risk", 0.0),
            new NumericField("reputation", "reputation", 100.0),
            new NumericField("history_factor", "history_factor", 0.0));

    private final boolean enabled;
    private final double maxBonus;
    /** 管理端发布出来的规则版本；为 {@code null} 表示只有随包内置规则（单元测试与最小部署）。 */
    private final RuleVersionStore versionStore;
    private final RuleHostContext hostContext = new RuleHostContext();

    /**
     * §2.15.2 的性能采样器，作为执行观察者挂在规则运行路径上。
     *
     * <p>随应用常驻、不设采样窗口：「这条规则现在有多慢」每次都得答得上，一断采样就答不上来。
     * 面板读到的就是进程启动至今的累计值。</p>
     */
    private final PrlProfiler profiler = new PrlProfiler();
    private final RuleManager manager = new RuleManager(hostContext, profiler);
    private final PrlCompiler compiler = new PrlCompiler(hostContext);
    private final PrlAnalyzer analyzer = new PrlAnalyzer(hostContext);

    /** 规则名 → 展示信息；与 {@link RuleManager} 里的装载集合保持同步。 */
    private volatile Map<String, RuleMeta> metas = Map.of();

    /** 单测直接 {@code new} 的构造：不带版本库，只装随包内置规则。 */
    public PrlRuleEngine(boolean enabled, double maxBonus) {
        this(enabled, maxBonus, null);
    }

    /**
     * 容器用的构造：把规则版本库接进来，已发布的规则就能覆盖内置版本。
     *
     * <p>两个构造并存（另一个是给单测直接 {@code new} 的），Spring 会因此无从选择，必须显式标注。</p>
     */
    @Autowired
    public PrlRuleEngine(@Value("${pacc.rules.enabled:true}") boolean enabled,
                        @Value("${pacc.rules.max-bonus:15}") double maxBonus,
                        RuleVersionStore versionStore) {
        this.enabled = enabled;
        this.maxBonus = maxBonus;
        this.versionStore = versionStore;
        if (enabled) {
            reload();
        }
    }

    /**
     * 应用就绪后再叠加一次版本库里已发布的规则。
     *
     * <p>表由 Flyway 建，而这个 bean 的构造时机不保证在迁移之后 —— 在构造函数里读库会在
     * 全新数据库上撞上「表不存在」。构造期只装内置规则，能读库时再覆盖一遍，两次都是幂等的。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (enabled) {
            reload();
        }
    }

    /**
     * 引擎共用的规则装载表。
     *
     * <p>发布流程直接作用在这一个实例上（{@code RuleReleaseManager.approve} 装的就是它），
     * 所以「审批通过」和「线上生效」是同一个动作，不存在发布成功但还在跑旧版本的中间态。</p>
     */
    public RuleManager manager() {
        return manager;
    }

    /** §2.15.2 的性能采样器，管理端性能面板从这里取报告。 */
    public PrlProfiler profiler() {
        return profiler;
    }

    /**
     * 重新加载 classpath 下的全部 PRL 规则，已删除的规则一并卸载。
     *
     * @return 装载成功的规则条数
     */
    public synchronized int reload() {
        List<String> loaded = new ArrayList<>();
        Map<String, RuleMeta> nextMetas = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RULE_LOCATION);
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null || !fileName.endsWith(RULE_SUFFIX)) {
                    continue;
                }
                String id = fileName.substring(0, fileName.length() - RULE_SUFFIX.length());
                try (InputStream in = resource.getInputStream()) {
                    String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    // 走 compileChecked 而不是 compile：编译产物还要喂给分析器取静态内存估算，
                    // 分析器直接吃 CompileResult，这样就只编译一次。
                    CompileResult compiled = compiler.compileChecked(source);
                    if (!compiled.ok()) {
                        throw compileFailure(compiled);
                    }
                    manager.loadBytecode(id, compiled.bytecode());
                    loaded.add(id);
                    RuleMeta meta = RuleMeta.of(id, fileName, compiled.bytecode());
                    nextMetas.put(id, meta);
                    profiler.register(id, meta.version(), estimatedHeapBytes(compiled, id));
                } catch (Exception e) {
                    // 单条编译失败不影响其余规则；同名旧规则保持原样。
                    log.warn("[PrlRuleEngine] 规则加载失败 {}: {}", fileName, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[PrlRuleEngine] 扫描规则目录失败: {}", e.getMessage());
        }
        overlayPublished(loaded, nextMetas);
        Set<String> keep = new LinkedHashSet<>(loaded);
        for (String existing : manager.ruleNames()) {
            if (!keep.contains(existing)) {
                manager.unloadRule(existing);
            }
        }
        this.metas = Map.copyOf(nextMetas);
        return loaded.size();
    }

    /**
     * 把编译失败折成异常。
     *
     * <p>{@code CompileResult.toException()} 只在 prl 包内可见，宿主这边自己取第一条错误 —— 与它
     * 的实现一致，异常里带的行号列号是日志排查最需要的那两个数。</p>
     */
    private static PrlException compileFailure(CompileResult compiled) {
        Diagnostic diagnostic = compiled.errors().get(0);
        return new PrlException(diagnostic.message(), diagnostic.line(), diagnostic.col());
    }

    /**
     * 规则静态估算的堆占用，作为性能面板「内存峰值」的基线。
     *
     * <p>宿主没有实测堆占用的手段（真去量就得开 Instrumentation），所以这个静态估算就是面板上那个
     * 数字。实测值比它大时以实测值计，这是 {@code PrlProfiler.register} 的既有语义。</p>
     */
    private long estimatedHeapBytes(CompileResult compiled, String ruleName) {
        return analyzer.analyze(compiled).metrics().stream()
                .filter(metrics -> ruleName.equals(metrics.ruleName()))
                .mapToLong(RuleMetrics::estimatedHeapBytes)
                .findFirst()
                .orElse(0L);
    }

    /**
     * 用版本库里已发布的规则覆盖内置规则。
     *
     * <p>覆盖在同一张装载表上做，所以「管理端改了规则」与「风控用的是哪个版本」不会分叉。
     * 读库失败只记日志：规则引擎退化成内置规则仍能检测，比整个风控接口起不来好。</p>
     */
    private void overlayPublished(List<String> loaded, Map<String, RuleMeta> nextMetas) {
        if (versionStore == null) {
            return;
        }
        try {
            for (RuleVersion version : versionStore.all()) {
                if (version.status() != RuleStatus.ACTIVE) {
                    continue;
                }
                PrlBytecode bytecode = PrlcFormat.read(version.bytecode());
                manager.loadBytecode(version.ruleName(), bytecode);
                if (!loaded.contains(version.ruleName())) {
                    loaded.add(version.ruleName());
                }
                nextMetas.put(version.ruleName(),
                        RuleMeta.of(version.ruleName(), version.ruleName() + RULE_SUFFIX, bytecode)
                                .withOrigin("published"));
                // 版本号跟着发布版本走；内存基线保留内置版本登记的值 —— 版本库里只有字节码，
                // 没有源码可估算。register 的第二个重载不会把已有的基线清零。
                profiler.register(version.ruleName(), version.version());
            }
        } catch (RuntimeException e) {
            log.error("[PrlRuleEngine] 读取规则版本库失败，本次只装载内置规则: {}", e.getMessage());
        }
    }

    /** 对给定事件上下文求值；命中明细按规则名字典序返回。 */
    public Evaluation evaluate(Map<String, Object> ctx) {
        if (!enabled) {
            return new Evaluation(List.of(), 0.0, 0);
        }
        Map<String, Object> input = toRuleInput(ctx == null ? Map.of() : ctx);
        List<RuleHit> hits = new ArrayList<>();
        double total = 0.0;
        int count = 0;
        for (String name : manager.ruleNames()) {
            Optional<RuleInstance> instance = manager.rule(name);
            if (instance.isEmpty() || !instance.get().isEnabled()) {
                continue;
            }
            count++;
            Optional<DetectionResult> alert = manager.executeRule(name, input);
            if (alert.isEmpty()) {
                continue;
            }
            RuleHit hit = toHit(alert.get());
            total += hit.score();
            hits.add(hit);
        }
        return new Evaluation(hits, Math.min(total, maxBonus), count);
    }

    /** 规则清单，供管理端展示。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : manager.ruleNames()) {
            RuleMeta meta = metas.get(name);
            Optional<RuleInstance> instance = manager.rule(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", name);
            row.put("file", meta == null ? name + RULE_SUFFIX : meta.file());
            row.put("name", meta == null ? name : meta.description());
            row.put("enabled", instance.map(RuleInstance::isEnabled).orElse(false));
            row.put("version", meta == null ? "" : meta.version());
            row.put("severity", meta == null ? "" : meta.severity());
            row.put("category", meta == null ? "" : meta.category());
            row.put("origin", meta == null ? "" : meta.origin());
            out.add(row);
        }
        return out;
    }

    /** 配置里规则引擎是否启用；关掉时 {@link #evaluate} 恒返回空。 */
    public boolean enabled() {
        return enabled;
    }

    private RuleHit toHit(DetectionResult alert) {
        Map<Object, Object> evidence = alert.evidence() == null ? Map.of() : alert.evidence();
        double score = asDouble(evidence.get(EVIDENCE_SCORE))
                .orElseGet(() -> alert.confidence() * maxBonus);
        String reason = asText(evidence.get(EVIDENCE_REASON)).orElse(alert.type());
        RuleMeta meta = metas.get(alert.ruleName());
        String display = meta == null || meta.description().isBlank()
                ? alert.ruleName()
                : meta.description();
        return new RuleHit(alert.ruleName(), display, score, reason);
    }

    /**
     * 把宿主上下文归一成规则 input 块声明的类型。
     *
     * <p>PRL 的 int/float 不能隐式混用，字符串参数拿到 null 也会在执行期炸，所以类型收敛
     * 必须在宿主这一侧做完。</p>
     */
    private Map<String, Object> toRuleInput(Map<String, Object> ctx) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (TextField field : TEXT_FIELDS) {
            input.put(field.ruleName(), asText(ctx.get(field.ctxKey())).orElse(""));
        }
        for (NumericField field : NUMERIC_FIELDS) {
            input.put(field.ruleName(), asDouble(ctx.get(field.ctxKey())).orElse(field.fallback()));
        }
        return input;
    }

    private static Optional<String> asText(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(value));
    }

    private static Optional<Double> asDouble(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            // 管理端试评接口允许直接贴字符串数字，与 Lua 的 tonumber 行为对齐。
            try {
                return Optional.of(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 一条规则的命中明细，字段与原先的 Lua 引擎一致。 */
    public record RuleHit(String id, String name, double score, String reason) {
    }

    /** 事件上下文键 → 规则 input 字段名。 */
    private record TextField(String ctxKey, String ruleName) {
    }

    /** 事件上下文键 → 规则 input 字段名 + 缺失时的缺省值。 */
    private record NumericField(String ctxKey, String ruleName, double fallback) {
    }

    /** 一次求值的结果。 */
    public record Evaluation(List<RuleHit> hits, double bonus, int ruleCount) {
    }

    /** 规则的展示信息，全部取自编译产物里的元数据。 */
    private record RuleMeta(String id, String file, String description, String version,
                            String severity, String category, String origin) {

        static RuleMeta of(String id, String file, PrlBytecode bytecode) {
            RuleEntry entry = bytecode.rule(id);
            if (entry == null) {
                return new RuleMeta(id, file, id, "", "", "", "bundled");
            }
            return new RuleMeta(id, file,
                    entry.metadataString("description", id),
                    entry.metadataString("version", ""),
                    entry.metadataString("severity", ""),
                    entry.metadataString("category", ""),
                    "bundled");
        }

        /** 标明这条规则来自版本库而不是随包内置，管理端排查「改了没生效」时第一眼看这个。 */
        RuleMeta withOrigin(String newOrigin) {
            return new RuleMeta(id, file, description, version, severity, category, newOrigin);
        }
    }
}