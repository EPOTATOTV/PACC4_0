package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.df.multimodal.FusedResult;
import com.potatotv.pacc.service.detection.df.multimodal.Modality;
import com.potatotv.pacc.service.detection.df.multimodal.ModalityInput;
import com.potatotv.pacc.service.detection.df.multimodal.ModalityScore;
import com.potatotv.pacc.service.detection.df.multimodal.MultimodalFusionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 多模态融合分析端点：一次请求内完成「五模态独立评分 + 指定策略融合」。
 *
 * <p>请求体：{@code {"strategy": "early|late|hybrid", "weights": {"input": 1.0},
 * "modalities": {"input": {"click_cps": 16.0}, "memory": {...}}}}。{@code modalities} 也可省略，
 * 直接把模态名放到顶层（例如 {@code "memory": {...}}）；未提供或载荷为空的模态按缺失处理，
 * 融合策略会重新归一化其余模态权重，不报错。</p>
 *
 * <p>响应：{@code {"modalityScores": [...], "fusedScore": 0.x, "verdict": "SAFE|SUSPICIOUS|CHEAT|CRITICAL",
 * "strategy": "...", "weightsUsed": {...}, "missingModalities": [...]}}。</p>
 *
 * <p>鉴权沿用现有管理端点约定（{@code /api/admin/**} + {@code @RequirePermission}），
 * 只做单次分析，不落库、不触发告警。</p>
 */
@RestController
@RequestMapping("/api/admin/df/multimodal")
@RequiredArgsConstructor
@SuppressWarnings("null") // 请求体 Map 泛型解析与枚举映射的 null 分析误报
public class DfMultimodalController {

    private final MultimodalFusionService fusionService;

    /** 执行一次多模态融合分析。未知策略返回 400；空策略按 hybrid。 */
    @PostMapping("/analyze")
    @RequirePermission("realtime:read")
    public ResponseEntity<?> analyze(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;

        Map<Modality, ModalityInput> inputs = collectInputs(payload);
        Map<String, Double> weights = doubleMapOf(payload.get("weights"));
        String strategy = str(payload.get("strategy"));

        FusedResult result;
        try {
            result = fusionService.analyze(strategy, inputs, weights);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> scores = new ArrayList<>(result.modalityScores().size());
        for (ModalityScore s : result.modalityScores()) {
            scores.add(scoreView(s));
        }
        out.put("modalityScores", scores);
        out.put("fusedScore", result.fusedScore());
        out.put("verdict", result.verdict());
        out.put("strategy", result.strategy());
        out.put("weightsUsed", keyed(result.weightsUsed()));
        out.put("missingModalities", result.missingModalities().stream().map(Modality::key).toList());
        out.put("contributions", keyed(result.contributions()));
        return ResponseEntity.ok(out);
    }

    // ------------------------------ 解析 ------------------------------

    /**
     * 抽取各模态载荷：优先读 {@code modalities} 子对象，其次读顶层同名键。
     * 同一模态两处都提供时，{@code modalities} 内优先。
     */
    private static Map<Modality, ModalityInput> collectInputs(Map<String, Object> payload) {
        Map<Modality, ModalityInput> inputs = new EnumMap<>(Modality.class);
        Object nested = payload.get("modalities");
        if (nested instanceof Map<?, ?> m) {
            for (Modality modality : Modality.values()) {
                Object raw = m.get(modality.key());
                ModalityInput input = inputOf(raw);
                if (input != null) {
                    inputs.put(modality, input);
                }
            }
        }
        for (Modality modality : Modality.values()) {
            if (inputs.containsKey(modality)) {
                continue;
            }
            ModalityInput input = inputOf(payload.get(modality.key()));
            if (input != null) {
                inputs.put(modality, input);
            }
        }
        return inputs;
    }

    /** 单个模态载荷 → {@link ModalityInput}；非对象或无数值项返回 null（视为缺失）。 */
    private static ModalityInput inputOf(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        boolean hasFeatureList = m.get("features") instanceof Map<?, ?>;
        Object source = hasFeatureList ? m.get("features") : m;
        Map<String, Double> features = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) source).entrySet()) {
            if (e.getKey() != null && e.getValue() instanceof Number n) {
                features.put(e.getKey().toString(), n.doubleValue());
            }
        }
        return features.isEmpty() ? null : new ModalityInput(features);
    }

    /** 权重覆盖对象 → 字符串键的数值映射（忽略非数值项）。 */
    private static Map<String, Double> doubleMapOf(Object raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    out.put(e.getKey().toString(), n.doubleValue());
                }
            }
        }
        return out;
    }

    /** 模态评分视图（响应体形状稳定）。 */
    private static Map<String, Object> scoreView(ModalityScore s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modality", s.modality().key());
        m.put("target", s.modality().target());
        m.put("present", s.present());
        m.put("score", s.score());
        m.put("confidence", s.confidence());
        m.put("evidence", s.evidence());
        return m;
    }

    /** 模态键 → 数值的有序映射（对外用小写模态名）。 */
    private static Map<String, Double> keyed(Map<Modality, Double> values) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Modality modality : Modality.values()) {
            Double v = values.get(modality);
            if (v != null) {
                out.put(modality.key(), v);
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}