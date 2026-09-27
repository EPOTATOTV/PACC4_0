package com.potatotv.prl.engine;

import java.util.List;
import java.util.Optional;

/**
 * 规则版本库（设计文档 §2.13.1）。
 *
 * <p>文档里它是一张数据库表 {@code prl_rule_versions}，但那张表属于管控后端，不属于这个引擎 ——
 * PRL 是零依赖的独立库，不能自带数据库。所以这里只留接口：管控后端用 JDBC 实现它，
 * 单测与本地开发用 {@link InMemoryRuleVersionStore}。</p>
 *
 * <p>接口刻意只做「存 / 取 / 列」，不做状态迁移 —— 迁移规则集中在
 * {@link RuleReleaseManager}（§2.13.2 的发布流程），这样换一个存储实现不会顺带换一套发布语义。</p>
 */
public interface RuleVersionStore {

    /** 写入一个版本；同（规则名, 版本号）已存在则覆盖。 */
    void save(RuleVersion version);

    Optional<RuleVersion> find(String ruleName, String version);

    /** 当前生效（{@code ACTIVE}）的版本；灰度中的 {@code TESTING} 不算。 */
    Optional<RuleVersion> active(String ruleName);

    /** 某条规则的版本列表，新版本在前。 */
    List<RuleVersion> history(String ruleName);

    /** 全部版本，按规则名与版本号排序。 */
    List<RuleVersion> all();
}