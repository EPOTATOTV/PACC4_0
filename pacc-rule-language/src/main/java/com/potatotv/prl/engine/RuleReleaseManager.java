package com.potatotv.prl.engine;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.analysis.PrlAnalyzer;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.bytecode.PrlcFormat;
import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.vm.PrlVm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则发布流程（设计文档 §2.13.2）。
 *
 * <p>把「编写 → 静态分析 → draft → 灰度 → 审批 → active → 回滚」这条链落成方法。三处状态迁移都
 * 卡在规则上，不是随手 {@code save}：</p>
 * <ul>
 *   <li>{@link #submitDraft} 先静态分析，有<strong>错误</strong>就不入库。冲突不拦 —— 冲突检测器
 *       （§2.14.2）分不出「两个结论互相矛盾」和「两个都合法的类型名」，拿它拦发布会在管理端造成
 *       无法绕过的死锁。冲突连同报告一起返回，由人判断。</li>
 *   <li>{@link #approve} 必须有审批人：§2.13.2 写明生产环境审批是必需的，接口里就不留「默认通过」的口子。</li>
 *   <li>{@link #rollback} 只走 {@code rollbackTo} 指到的那一个版本，不做「随便挑个旧版本」的灵活回滚 ——
 *       出事的时候需要的是确定性和一步到位。</li>
 * </ul>
 *
 * <p>发布成 {@code ACTIVE} 的瞬间会把规则装进 {@link RuleManager}（原子替换），所以「发布」和
 * 「生效」是同一个动作，不存在发布成功但线上还跑着老版本的中间态。</p>
 */
public final class RuleReleaseManager {

    private final RuleVersionStore store;
    private final RuleManager manager;
    private final PrlCompiler compiler;
    private final PrlAnalyzer analyzer;
    private final PrlVm vm;

    public RuleReleaseManager(RuleVersionStore store, RuleManager manager) {
        this.store = store;
        this.manager = manager;
        this.compiler = new PrlCompiler(manager.host());
        this.analyzer = new PrlAnalyzer(manager.host());
        // 灰度对比要同时跑新旧两个版本，不能拿 RuleManager 硬塞（那会把线上版本换掉）。
        this.vm = new PrlVm(manager.host());
    }

    // ------------------------------------------------------------------ 流程

    /** 静态分析，不落库。管理端编辑器保存前先调这个（§2.13.2 第一步）。 */
    public AnalysisResult analyze(String source) {
        return analyzer.analyze(source);
    }

    /**
     * 提交为 {@code DRAFT}（§2.13.2 第三步）。
     *
     * @throws PrlException 静态分析有错误、规则名对不上、或同版本号已有不同内容
     */
    public RuleVersion submitDraft(String ruleName, String version, String source, String author) {
        CompileResult compile = compiler.compileChecked(source);
        AnalysisResult analysis = analyzer.analyze(compile);
        if (!analysis.errors().isEmpty()) {
            throw new PrlException("规则 " + ruleName + " 静态分析未通过：\n" + analysis.report());
        }
        PrlBytecode bytecode = compile.bytecode();
        if (bytecode.rule(ruleName) == null) {
            throw new PrlException("源码里没有名为 '" + ruleName + "' 的规则，"
                    + "版本库按规则名索引，不允许提交名不符实的版本");
        }
        RuleVersion draft = new RuleVersion(ruleName, version, source, PrlcFormat.write(bytecode),
                author, RuleStatus.DRAFT, System.currentTimeMillis());
        Optional<RuleVersion> existing = store.find(ruleName, version);
        if (existing.isPresent() && !existing.get().checksum().equals(draft.checksum())) {
            throw new PrlException("版本 " + ruleName + " v" + version
                    + " 已存在且内容不同（已有 checksum " + existing.get().checksum()
                    + "），请换一个版本号");
        }
        store.save(draft);
        return draft;
    }

    /** 进入灰度：{@code DRAFT → TESTING}。灰度流量由宿主按 PTEID 抽样，引擎只负责标状态与提供对比。 */
    public RuleVersion startCanary(String ruleName, String version) {
        RuleVersion current = require(ruleName, version);
        if (current.status() != RuleStatus.DRAFT) {
            throw new PrlException("只有 draft 版本能进灰度，当前是 " + current.status());
        }
        RuleVersion testing = current.withStatus(RuleStatus.TESTING, null, current.rollbackTo());
        store.save(testing);
        return testing;
    }

    /**
     * 审批并发布为 {@code ACTIVE}（§2.13.2 第五步）。
     *
     * <p>旧的 active 变 {@code DEPRECATED}，并把自己回填成新版本的回滚目标。</p>
     */
    public RuleVersion approve(String ruleName, String version, String approver) {
        if (approver == null || approver.isBlank()) {
            throw new PrlException("发布 " + ruleName + " v" + version + " 需要审批人（§2.13.2）");
        }
        RuleVersion candidate = require(ruleName, version);
        if (candidate.status() != RuleStatus.DRAFT && candidate.status() != RuleStatus.TESTING) {
            throw new PrlException("只有 draft/testing 版本能发布，当前是 " + candidate.status());
        }
        Optional<RuleVersion> previous = store.active(ruleName);
        previous.ifPresent(old -> store.save(old.withStatus(RuleStatus.DEPRECATED, old.approvedBy(), null)));

        RuleVersion active = candidate.withStatus(RuleStatus.ACTIVE, approver,
                previous.map(RuleVersion::version).orElse(null));
        store.save(active);
        activate(active);
        return active;
    }

    /** 一键回滚到 {@code rollbackTo}（§2.13.2 最后一步）。回滚掉的版本标记为 {@code DISABLED}。 */
    public RuleVersion rollback(String ruleName) {
        RuleVersion current = store.active(ruleName)
                .orElseThrow(() -> new PrlException("规则 " + ruleName + " 没有生效版本，无从回滚"));
        String target = current.rollbackTo();
        if (target == null) {
            throw new PrlException("规则 " + ruleName + " v" + current.version() + " 没有记录回滚目标");
        }
        RuleVersion previous = require(ruleName, target);
        if (previous.status() == RuleStatus.DISABLED) {
            throw new PrlException("回滚目标 " + ruleName + " v" + target + " 已被禁用，不能回滚到它");
        }
        store.save(current.withStatus(RuleStatus.DISABLED, current.approvedBy(), target));
        RuleVersion restored = previous.withStatus(RuleStatus.ACTIVE, previous.approvedBy(), null);
        store.save(restored);
        activate(restored);
        return restored;
    }

    // ------------------------------------------------------------------ 灰度对比

    /**
     * 同一份输入分别跑基线版本与候选版本，给出结论是否一致（§2.13.2 的「对比新旧规则结果」）。
     *
     * <p>误报率阈值的判断在宿主侧：它才有真实流量与人工标注。这里只提供「同输入下两次结论是否一致」，
     * 因为这是引擎唯一能确定回答的部分。</p>
     */
    public CanaryComparison compare(RuleVersion baseline, RuleVersion candidate, Map<String, Object> input) {
        return new CanaryComparison(runOnce(baseline, input), runOnce(candidate, input));
    }

    private DetectionResult runOnce(RuleVersion version, Map<String, Object> input) {
        PrlBytecode program = PrlcFormat.read(version.bytecode());
        Object result = program.rule(version.ruleName()) != null
                ? vm.executeRule(program, version.ruleName(), input)
                : vm.execute(program, input);
        return result instanceof DetectionResult detection
                ? (detection.ruleName() == null || detection.ruleName().isEmpty()
                        ? detection.withRuleName(version.ruleName())
                        : detection)
                : null;
    }

    // ------------------------------------------------------------------ 查询

    public List<RuleVersion> versions(String ruleName) {
        return store.history(ruleName);
    }

    public Optional<RuleVersion> active(String ruleName) {
        return store.active(ruleName);
    }

    private void activate(RuleVersion version) {
        manager.loadBytecode(version.ruleName(), PrlcFormat.read(version.bytecode()));
    }

    private RuleVersion require(String ruleName, String version) {
        return store.find(ruleName, version)
                .orElseThrow(() -> new PrlException("版本库里没有 " + ruleName + " v" + version));
    }
}