package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.service.detection.DetectionAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.1 检测分析接口（受 X-Admin-Key 保护）：
 * 暴力外挂四层引擎 / 隐身外挂五层引擎 / AI 行为画像融合分析。
 */
@RestController
@RequestMapping("/api/admin/v41")
@RequiredArgsConstructor
@SuppressWarnings("null") // 128 维特征流 lambda 的 Eclipse JDT null 分析误报
public class Detection41Controller {

    private final DetectionAnalysisService analysisService;

    /** 一键检测演示（作弊特征）。 */
    @GetMapping("/demo/cheat")
    public Map<String, Object> demoCheat() {
        return analysisService.analyze(DetectionAnalysisService.demoFeatureVector(),
                DetectionAnalysisService.emptyContext());
    }

    /** 一键检测演示（人类正常特征）。 */
    @GetMapping("/demo/human")
    public Map<String, Object> demoHuman() {
        return analysisService.analyze(DetectionAnalysisService.humanFeatureVector(),
                DetectionAnalysisService.emptyContext());
    }

    /** 自定义 128 维特征分析。 */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Double> features) {
        FeatureVector fv = new FeatureVector();
        features.forEach(fv::set);
        return analysisService.analyze(fv, new LinkedHashMap<>());
    }
}
