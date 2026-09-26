package com.potatotv.prl.profiler;

/**
 * 一个热点函数的耗时占比（设计文档 §2.15.2 的「热点函数」列表）。
 *
 * @param name      函数名（规则里的辅助函数、lambda、宿主函数、标准库函数都可能出现在这里）
 * @param calls     调用次数
 * @param selfNanos 自身耗时之和，不含它调用的子函数
 * @param share     占「全部函数自身耗时」的比例，0~1
 */
public record FunctionHotspot(String name, long calls, long selfNanos, double share) {

    @Override
    public String toString() {
        return name + " " + Math.round(share * 100) + "% (" + calls + " 次, "
                + ProfilerReport.formatDuration(selfNanos) + ")";
    }
}