package com.potatotv.paccclient.plugin;

/**
 * PRL 动态规则注册入口（{@code PluginContext.registerRule} 的落地目标）。
 *
 * <p>宿主把它接到 {@code PrlDetectionEngine.updateRule}：插件规则与内置规则走同一条
 * 「编译通过才替换」的链路，编译失败只影响该条规则。</p>
 */
@FunctionalInterface
public interface RuleRegistrar {

    /**
     * 注册 / 替换一条规则。
     *
     * @param cheatType 作弊类型 code（必须是宿主已知的 {@code CheatType}）
     * @param prlScript PRL 脚本源码
     */
    void register(String cheatType, String prlScript);
}