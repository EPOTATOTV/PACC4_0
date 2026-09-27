package com.potatotv.prl.analysis;

/**
 * 两条规则之间的冲突（设计文档 §2.14.2）。
 *
 * <p>检测方式是把 {@code when} 条件打印成规范化字符串后比对：条件完全相同、但告警类型不同，
 * 就是「同一现象被两条规则判成相反结论」的候选。这个判据抓得住 §2.14.2 的例子，也抓得住阈值被
 * 复制粘贴后忘记改类型的写法，但它<strong>分不出</strong>「{@code SUSPICIOUS} 与 {@code NORMAL}」
 * 和「两个都合法但名字不同的类型」，所以只报建议，不报错误。</p>
 *
 * @param ruleA      规则名（字典序在前）
 * @param ruleB      规则名
 * @param condition  两条规则共用的条件文本
 * @param reason     冲突描述
 * @param suggestion 处理建议
 */
public record RuleConflict(String ruleA, String ruleB, String condition, String reason, String suggestion) {

    @Override
    public String toString() {
        return "冲突：" + ruleA + " 与 " + ruleB + " 在 " + condition + " 时 " + reason
                + "；建议：" + suggestion;
    }
}