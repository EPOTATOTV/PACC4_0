package com.potatotv.prl.stdlib;

import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;

/**
 * {@code interval_stats} 的返回值（§2.4.2 的 {@code Stats}）。
 *
 * <p>字段与 §2.3.3 的 {@code Stats} 类型一致：{@code mean}/{@code stddev}/{@code median} 是 float，
 * {@code count} 是 int（区间个数，即事件数 - 1）。</p>
 */
public final class StatsValue implements PrlHostObject {

    private final double mean;
    private final double stddev;
    private final double median;
    private final long count;

    public StatsValue(double mean, double stddev, double median, long count) {
        this.mean = mean;
        this.stddev = stddev;
        this.median = median;
        this.count = count;
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "mean" -> mean;
            case "stddev" -> stddev;
            case "median" -> median;
            case "count" -> count;
            default -> throw new PrlSecurityException("Stats 没有成员 '" + member + "'，可用成员："
                    + describableMembers());
        };
    }

    @Override
    public String describableMembers() {
        return "mean, stddev, median, count";
    }
}