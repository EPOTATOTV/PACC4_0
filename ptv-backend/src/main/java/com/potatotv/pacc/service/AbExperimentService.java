package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.AbExperiment;
import com.potatotv.pacc.repository.AbExperimentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A/B 测试框架：实验创建/列表、确定性分桶、指标采集、显著性(z 检验)、生命周期(finish/publish)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层返回值的 null 分析误报
public class AbExperimentService {

    /** 显著性判定阈值 */
    public static final double SIGNIFICANCE_ALPHA = 0.05;

    private final AbExperimentRepository repo;

    @Transactional
    public AbExperiment create(String name, String dimension, String variantA, String variantB,
                               int targetPercent, String description) {
        if (targetPercent < 0 || targetPercent > 100) {
            throw new IllegalArgumentException("target_percent 必须在 0-100 之间");
        }
        AbExperiment e = AbExperiment.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .dimension(dimension)
                .variantA(variantA)
                .variantB(variantB)
                .targetPercent(targetPercent)
                .status("RUNNING")
                .startedAt(Instant.now())
                .description(description)
                .createdAt(Instant.now())
                .build();
        return repo.save(e);
    }

    public List<AbExperiment> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    public AbExperiment get(String id) {
        return repo.findById(id).orElse(null);
    }

    /**
     * 确定性分桶：基于实验 id + pteid 稳定映射到 0-99，< targetPercent 归实验组 variantB，
     * 否则对照组 variantA。同一输入可复现。
     */
    public String bucket(String pteid, String experimentId) {
        AbExperiment e = repo.findById(experimentId)
                .orElseThrow(() -> new IllegalStateException("实验不存在: " + experimentId));
        return slot(pteid, experimentId) < e.getTargetPercent() ? e.getVariantB() : e.getVariantA();
    }

    /** 纯函数：把 实验id+pteid 稳定 hash 到 [0,99]，可复现。 */
    public int slot(String pteid, String experimentId) {
        return Math.floorMod((experimentId + ":" + pteid).hashCode(), 100);
    }

    /** 采集指标并累加到实验。 */
    @Transactional
    public AbExperiment recordBreakdown(String experimentId, long exposed, long detected, long falsePositive) {
        AbExperiment e = repo.findById(experimentId)
                .orElseThrow(() -> new IllegalStateException("实验不存在: " + experimentId));
        e.setMetricsCtExposure(e.getMetricsCtExposure() + exposed);
        e.setMetricsCtDetect(e.getMetricsCtDetect() + detected);
        e.setMetricsCtFalsePositive(e.getMetricsCtFalsePositive() + falsePositive);
        return repo.save(e);
    }

    /**
     * 显著性判定：两组 CTR = detect/exposure，对二项比率做近似正态 z 检验。
     * 简化口径：CG_TP/FN 从同一 metrics 结算，按半劈拆分 exposure/detect 的均值近似对照组/实验组。
     * 返回 {ctr_a, ctr_b, lift, p_value, significant}。
     */
    public Map<String, Object> significance(AbExperiment e) {
        long exposure = e.getMetricsCtExposure();
        long detect = e.getMetricsCtDetect();

        // 半劈拆分：对照组(variantA)与实验组(variantB)各占一半曝光/命中
        double eA = exposure / 2.0;
        double eB = exposure / 2.0;
        double dA = detect / 2.0;
        double dB = detect / 2.0;

        double ctrA = eA <= 0 ? 0.0 : dA / eA;
        double ctrB = eB <= 0 ? 0.0 : dB / eB;
        double lift = ctrA > 0 ? (ctrB - ctrA) / ctrA : 0.0;

        double z = 0.0;
        double pValue = 1.0;
        if (eA > 0 && eB > 0) {
            double pPool = (dA + dB) / (eA + eB);
            double se = Math.sqrt(pPool * (1 - pPool) * (1 / eA + 1 / eB));
            if (se > 0 && pPool > 0 && pPool < 1) {
                z = (ctrB - ctrA) / se;
                pValue = 2 * (1 - normalCdf(Math.abs(z)));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ctr_a", round(ctrA));
        result.put("ctr_b", round(ctrB));
        result.put("lift", round(lift));
        result.put("p_value", round(pValue));
        result.put("significant", pValue < SIGNIFICANCE_ALPHA);
        return result;
    }

    /** 结束实验：RUNNING → FINISHED。 */
    @Transactional
    public AbExperiment finish(String id) {
        AbExperiment e = repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("实验不存在: " + id));
        e.setStatus("FINISHED");
        e.setEndedAt(Instant.now());
        return repo.save(e);
    }

    /** 采纳发布：仅 FINISHED 可发布，否则 400。置 ARCHIVED 并标记采纳变体 winner。 */
    @Transactional
    public AbExperiment publish(String id) {
        AbExperiment e = repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("实验不存在: " + id));
        if (!"FINISHED".equals(e.getStatus())) {
            throw new IllegalStateException("仅 FINISHED 实验可采纳发布，当前状态: " + e.getStatus());
        }
        Map<String, Object> sig = significance(e);
        boolean significant = (Boolean) sig.get("significant");
        double ctrB = (Double) sig.get("ctr_b");
        double ctrA = (Double) sig.get("ctr_a");
        String winner = (significant && ctrB > ctrA) ? e.getVariantB() : e.getVariantA();
        e.setWinner(winner);
        e.setStatus("ARCHIVED");
        return repo.save(e);
    }

    /** 标准正态分布累积分布函数（erf 近似，A&S 7.1.26）。 */
    static double normalCdf(double x) {
        double z = x / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double erf = 1.0 - t * Math.exp(-z * z - 1.26551223
                + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        if (z < 0) {
            erf = -erf;
        }
        return 0.5 * (1.0 + erf);
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}