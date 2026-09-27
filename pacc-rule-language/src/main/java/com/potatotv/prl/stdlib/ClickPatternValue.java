package com.potatotv.prl.stdlib;

import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;

/**
 * {@code analyze_click_pattern} 的返回值（§2.4.6 的 {@code ClickPattern}）。
 *
 * <p>实现 {@link PrlHostObject} 让规则里的 {@code pattern.cps}、{@code pattern.is_human_like()}、
 * {@code pattern.to_json()} 在脱离 PACC 时也能跑。{@code to_json} 手写，不引第三方 JSON 库
 * （仓库硬约束：运行时零依赖）。</p>
 */
public final class ClickPatternValue implements PrlHostObject {

    /**
     * 人类点击的间隔标准差下限（ms）。机器人连点间隔几乎恒定，标准差趋近 0；
     * 人手抖动通常在几十毫秒量级。文档未给数值，取 5ms 作为启发式分界。
     */
    private static final double HUMAN_INTERVAL_STDDEV_MIN = 5.0;

    /** 人类 CPS 上限，超过这个值（长期维持）更像脚本；文档未给数值，取 15。 */
    private static final double HUMAN_CPS_MAX = 15.0;

    private final double cps;
    private final double intervalStddev;

    public ClickPatternValue(double cps, double intervalStddev) {
        this.cps = cps;
        this.intervalStddev = intervalStddev;
    }

    public double cps() {
        return cps;
    }

    public double intervalStddev() {
        return intervalStddev;
    }

    /** 判据集中在这里，{@code is_human_click_pattern} 复用同一套阈值。 */
    public static boolean isHumanLike(double cps, double intervalStddev) {
        return intervalStddev >= HUMAN_INTERVAL_STDDEV_MIN && cps <= HUMAN_CPS_MAX;
    }

    public boolean isHumanLike() {
        return isHumanLike(cps, intervalStddev);
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "cps" -> cps;
            case "interval_stddev" -> intervalStddev;
            default -> throw new PrlSecurityException("ClickPattern 没有成员 '" + member + "'，可用成员："
                    + describableMembers());
        };
    }

    @Override
    public Object callMethod(String method, Object[] args) {
        if (args == null || args.length != 0) {
            throw new PrlSecurityException("ClickPattern." + method + " 不接受参数");
        }
        return switch (method) {
            case "is_human_like" -> isHumanLike();
            case "to_json" -> toJson();
            default -> throw new PrlSecurityException("ClickPattern 没有方法 '" + method + "'，可用方法："
                    + "is_human_like, to_json");
        };
    }

    private String toJson() {
        return "{\"cps\":" + cps + ",\"interval_stddev\":" + intervalStddev + "}";
    }

    @Override
    public String describableMembers() {
        return "cps, interval_stddev";
    }
}