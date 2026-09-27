package com.potatotv.pacc.rule;

import com.potatotv.pacc.service.PcuUpdateService;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.bytecode.PrlcFormat;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleReleaseManager;
import com.potatotv.prl.engine.RuleStatus;
import com.potatotv.prl.engine.RuleVersion;
import com.potatotv.prl.vm.PrlVm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 规则发布链路（设计文档 §2.13.2 / §3.3）。
 *
 * <p>把 PRL 库的 {@link RuleReleaseManager} 接到 PACC 的两端：一端是管理端编辑器
 * （静态分析、提交草稿、灰度、审批、回滚），另一端是玩家端下发
 * （{@link #deliveriesFor(String)} 决定某台设备该拿哪个版本）。</p>
 *
 * <p><b>为什么灰度比例要单独存一张表。</b>{@link RuleVersion} 是 PRL 库对「版本」的定义，
 * 里面没有也不该有「放量到百分之几」——那是宿主的分流策略，同一个版本在不同宿主的放量节奏
 * 可以完全不同。所以比例落在 {@code prl_rule_rollout}，由本类读写。</p>
 *
 * <p><b>分桶口径与 PCU 一致。</b>直接复用 {@link PcuUpdateService#bucketOf(String)}：如果规则灰度
 * 自己另算一套哈希，同一台设备在「客户端更新」和「规则下发」里会落在不同人群，两套灰度的观测
 * 数据就没法互相印证。</p>
 */
@Service
public class PrlRuleReleaseService {

    private static final Logger log = LoggerFactory.getLogger(PrlRuleReleaseService.class);

    /** 审批发布即全量：灰度放量的过程在 {@code TESTING} 阶段走完，发布是终点不是起点。 */
    static final int FULL_ROLLOUT_PERCENT = 100;

    /** 编辑器默认的灰度起步比例（设计文档 §2.13.2 的 1%）。 */
    static final int DEFAULT_CANARY_PERCENT = 1;

    private final JdbcRuleVersionStore store;
    private final PrlRuleEngine ruleEngine;
    private final RuleReleaseManager releases;

    public PrlRuleReleaseService(JdbcRuleVersionStore store, PrlRuleEngine ruleEngine) {
        this.store = store;
        this.ruleEngine = ruleEngine;
        // 发布链路与风控求值共用同一个 RuleManager：审批通过就等于线上生效，没有中间态。
        this.releases = new RuleReleaseManager(store, ruleEngine.manager());
    }

    // ------------------------------------------------------------------ 编辑器

    /** 静态分析，不落库。编译器要报的错全在这里给出去（含冲突建议）。 */
    public AnalysisResult analyze(String source) {
        return releases.analyze(source);
    }

    /**
     * 提交草稿。
     *
     * @throws PrlException 静态分析有错误、源码里的规则名与入参不符、或同版本号已有不同内容
     */
    public RuleVersion submitDraft(String ruleName, String version, String source, String author) {
        return releases.submitDraft(ruleName, version, source, author);
    }

    /**
     * 进入灰度或调整放量比例。
     *
     * <p>两种调用都走这里：{@code DRAFT → TESTING} 的首次进入，以及灰度期间把比例从 1% 调到
     * 10%、50%、100%。后者只改比例、不动版本状态。</p>
     *
     * <p>已在 {@code TESTING} 的版本不能再交给库的 {@code startCanary}：它只认 {@code DRAFT}，
     * 重复调用会直接抛错。而「放量到百分之几」本来就是宿主的分流策略，不归库管，所以这一支在本地改比例。</p>
     */
    public RuleVersion startCanary(String ruleName, String version, Integer playerPercent) {
        int percent = playerPercent == null ? DEFAULT_CANARY_PERCENT : playerPercent;
        RuleVersion current = store.find(ruleName, version)
                .orElseThrow(() -> new PrlException("版本库里没有 " + ruleName + " v" + version));
        if (current.status() == RuleStatus.TESTING) {
            store.setRolloutPercent(ruleName, percent);
            return current;
        }
        RuleVersion testing = releases.startCanary(ruleName, version);
        store.setRolloutPercent(ruleName, percent);
        // 灰度版本不进 RuleManager（没到发布就不该影响线上求值），所以这里不 reload。
        return testing;
    }

    /**
     * 审批发布：{@code DRAFT/TESTING → ACTIVE}，并回到全量。
     *
     * <p>发布后立刻 {@link PrlRuleEngine#reload()}：{@code approve} 只负责把字节码装进
     * {@code RuleManager}，引擎侧那份规则元数据（管理端清单里的版本、来源）要重新扫一遍才对得上。</p>
     */
    public RuleVersion approve(String ruleName, String version, String approver) {
        RuleVersion active = releases.approve(ruleName, version, approver);
        store.setRolloutPercent(ruleName, FULL_ROLLOUT_PERCENT);
        ruleEngine.reload();
        log.info("[PRL] 规则已发布 {} v{}，审批人 {}", ruleName, version, approver);
        return active;
    }

    /**
     * 一键回滚。
     *
     * <p>{@code targetVersion} 是编辑器列表里用户点的那一个，它必须等于当前生效版本记录的回滚目标
     * （也就是被它取代的上一个 active）。PRL 库刻意只支持「回到上一个 active」，不允许随手挑任意
     * 旧版本 —— 出事的时候要的是确定性，不是灵活性。对不上就报错说清楚，而不是悄悄回滚到别处。</p>
     *
     * @throws PrlException 没有生效版本、没有回滚目标、目标被禁用、或与入参不一致
     */
    public RuleVersion rollback(String ruleName, String targetVersion) {
        RuleVersion current = releases.active(ruleName)
                .orElseThrow(() -> new PrlException("规则 " + ruleName + " 没有生效版本，无从回滚"));
        String expected = current.rollbackTo();
        if (targetVersion != null && !targetVersion.isBlank() && !targetVersion.equals(expected)) {
            throw new PrlException("规则 " + ruleName + " v" + current.version() + " 的回滚目标是 "
                    + (expected == null ? "（未记录）" : "v" + expected)
                    + "，不能回滚到 v" + targetVersion);
        }
        RuleVersion restored = releases.rollback(ruleName);
        store.setRolloutPercent(ruleName, FULL_ROLLOUT_PERCENT);
        ruleEngine.reload();
        log.warn("[PRL] 规则已回滚 {} v{} → v{}", ruleName, current.version(), restored.version());
        return restored;
    }

    // ------------------------------------------------------------------ 查询

    public List<RuleVersion> versions(String ruleName) {
        return ruleName == null || ruleName.isBlank() ? store.all() : store.history(ruleName);
    }

    public Optional<RuleVersion> active(String ruleName) {
        return store.active(ruleName);
    }

    public Optional<RuleVersion> find(String ruleName, String version) {
        return store.find(ruleName, version);
    }

    /** 某条规则当前的放量比例；没登记过按全量。 */
    public int rolloutPercent(String ruleName) {
        Integer percent = store.rolloutPercents().get(ruleName);
        return percent == null ? JdbcRuleVersionStore.DEFAULT_ROLLOUT_PERCENT : percent;
    }

    /** 版本库里出现过的每条规则的放量比例；没登记过的补成全量，管理端不用自己兜底。 */
    public Map<String, Integer> rolloutPercents() {
        Map<String, Integer> out = new LinkedHashMap<>(store.rolloutPercents());
        for (RuleVersion version : store.all()) {
            out.putIfAbsent(version.ruleName(), JdbcRuleVersionStore.DEFAULT_ROLLOUT_PERCENT);
        }
        return out;
    }

    /**
     * 试跑一条已入库的版本。
     *
     * @throws PrlException 版本库里没有这个版本
     */
    public Optional<DetectionResult> testRun(String ruleName, String version, Map<String, Object> input) {
        RuleVersion target = releases.active(ruleName).filter(v -> v.version().equals(version))
                .or(() -> store.find(ruleName, version))
                .orElseThrow(() -> new PrlException("版本库里没有 " + ruleName + " v" + version));
        PrlVm vm = new PrlVm(ruleEngine.manager().host());
        Object result = vm.executeRule(PrlcFormat.read(target.bytecode()), ruleName,
                input == null ? Map.of() : input);
        return result instanceof DetectionResult detection ? Optional.of(detection) : Optional.empty();
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 某台设备该拿到哪些规则。
     *
     * <p>每条规则的候选版本取「最新的 {@code TESTING}」优先于「{@code ACTIVE}」：灰度组拿新版本，
     * 灰度之外拿正在生效的旧版本；如果还没有旧版本，灰度之外的设备就暂时拿不到这条规则 —— 这正是
     * 灰度该有的样子，而不是让新规则直接铺开。</p>
     *
     * <p>比例只对「候选版本」生效，所以一条规则从 canary 1% 逐步调到 100% 再到审批发布，全程只有
     * 一个数字在动，运维不需要理解两套比例。</p>
     */
    public List<RuleDelivery> deliveriesFor(String pteid) {
        int bucket = PcuUpdateService.bucketOf(pteid);
        Map<String, Integer> percents = store.rolloutPercents();
        Map<String, RuleVersion> actives = new LinkedHashMap<>();
        Map<String, RuleVersion> testings = new LinkedHashMap<>();
        for (RuleVersion version : store.all()) {
            // all() 按 rule_name 升序、created_at 降序，同名第一条就是最新的
            if (version.status() == RuleStatus.ACTIVE) {
                actives.putIfAbsent(version.ruleName(), version);
            } else if (version.status() == RuleStatus.TESTING) {
                testings.putIfAbsent(version.ruleName(), version);
            }
        }
        List<RuleDelivery> out = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(actives.keySet());
        names.addAll(testings.keySet());
        for (String name : names) {
            Integer percent = percents.get(name);
            int rollout = percent == null ? JdbcRuleVersionStore.DEFAULT_ROLLOUT_PERCENT : percent;
            RuleVersion testing = testings.get(name);
            RuleVersion picked = testing != null && bucket < rollout ? testing : actives.get(name);
            if (picked != null) {
                out.add(RuleDelivery.of(picked));
            }
        }
        return out;
    }

    /**
     * 一个可下发的规则版本。
     *
     * @param name     规则名
     * @param version  版本号
     * @param checksum 源码 SHA-256（端侧据此判断是否需要重新下载）
     * @param status   状态，小写，仅用于端侧日志与排查
     * @param source   PRL 源码，端侧下载后编译
     */
    public record RuleDelivery(String name, String version, String checksum, String status, String source) {

        static RuleDelivery of(RuleVersion version) {
            return new RuleDelivery(version.ruleName(), version.version(), version.checksum(),
                    version.status().name().toLowerCase(Locale.ROOT), version.source());
        }
    }
}