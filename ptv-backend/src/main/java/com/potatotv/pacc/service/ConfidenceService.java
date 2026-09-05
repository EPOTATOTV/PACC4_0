package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ConfidenceTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 检测置信度分级服务（v4.4）。
 * <p>读取检测阈值配置，将综合风险评分映射为三级置信度。</p>
 */
@Service
public class ConfidenceService {

    private final int mediumThreshold;
    private final int highThreshold;

    public ConfidenceService(@Value("${pacc.detection.suspicious-low:70}") int mediumThreshold,
                             @Value("${pacc.detection.redscreen-threshold:85}") int highThreshold) {
        this.mediumThreshold = mediumThreshold;
        this.highThreshold = highThreshold;
    }

    /** 按当前阈值对风险分判定置信度等级。 */
    public ConfidenceTier classify(int riskScore) {
        return ConfidenceTier.of(riskScore, mediumThreshold, highThreshold);
    }

    public int mediumThreshold() {
        return mediumThreshold;
    }

    public int highThreshold() {
        return highThreshold;
    }
}