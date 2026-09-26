package com.potatotv.prl.engine;

/** 灰度对比的一次结果（设计文档 §2.13.2 的「对比新旧规则结果」）。 */
public record CanaryComparison(DetectionResult baseline, DetectionResult candidate) {

    /** 两次是否给出同样的结论。都用规则名与时间戳之外的字段比：那两项天然不同。 */
    public boolean agrees() {
        if (baseline == null || candidate == null) {
            return baseline == candidate;
        }
        return baseline.type().equals(candidate.type())
                && Math.abs(baseline.confidence() - candidate.confidence()) < 1e-9;
    }

    /** 置信度差（新版本减去旧版本）；任一为空时返回 0。 */
    public double confidenceDelta() {
        if (baseline == null || candidate == null) {
            return 0d;
        }
        return candidate.confidence() - baseline.confidence();
    }

    public String report() {
        if (agrees()) {
            return "灰度一致：type=" + (candidate == null ? "无告警" : candidate.type());
        }
        return "灰度不一致：旧 " + describe(baseline) + "，新 " + describe(candidate);
    }

    private static String describe(DetectionResult result) {
        return result == null ? "无告警" : result.type() + "@" + result.confidence();
    }
}