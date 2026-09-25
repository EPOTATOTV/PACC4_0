package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.df.streaming.StreamEvent;
import com.potatotv.pacc.service.detection.df.streaming.StreamVerdict;
import com.potatotv.pacc.service.detection.df.streaming.StreamingDetectionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DF §4.1.1 实时流式检测端点：事件流接入（{@code POST /ingest}）与运行指标（{@code GET /metrics}）。
 *
 * <p>鉴权沿用现有管理端点约定：{@code /api/admin/**} 由 {@code AdminKeyFilter} 校验，
 * 方法级权限走 {@code @RequirePermission}（{@code realtime} 模块），与既有实时监控接口一致。</p>
 */
@RestController
@RequestMapping("/api/admin/df/stream")
@RequiredArgsConstructor
@SuppressWarnings("null") // 请求体 Map 泛型解析的 null 分析误报
public class DfStreamController {

    private final StreamingDetectionEngine engine;

    /**
     * 接入一批端侧事件并同步返回判定。
     *
     * <p>请求体：{@code {"events": [{"pteid": "...", "event_type": "auto_clicker", "severity": "high",
     * "signal": 16.5, "features": {"feature_x": 1.0}}, ...]}}</p>
     *
     * <p>响应：{@code {"received": n, "accepted": n, "dropped": n, "verdict": {...}, "event_verdicts": [...]}}，
     * 其中 {@code verdict} 为整批中风险最高的那条判定。</p>
     */
    @PostMapping("/ingest")
    @RequirePermission("realtime:update")
    public ResponseEntity<?> ingest(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("events");
        if (!(raw instanceof List<?> rawEvents) || rawEvents.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "events 不能为空"));
        }
        List<StreamEvent> events = new ArrayList<>(rawEvents.size());
        for (Object item : rawEvents) {
            if (item instanceof Map<?, ?> m) {
                events.add(toEvent(m));
            }
        }
        if (events.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "events 中没有可解析的事件对象"));
        }

        List<StreamVerdict> verdicts = engine.ingest(events);
        StreamVerdict top = verdicts.stream()
                .max(Comparator.comparingDouble(StreamVerdict::riskScore))
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", events.size());
        out.put("accepted", verdicts.size());
        out.put("dropped", events.size() - verdicts.size());
        out.put("verdict", top == null ? emptyVerdict() : verdictView(top));
        List<Map<String, Object>> details = new ArrayList<>(verdicts.size());
        for (StreamVerdict v : verdicts) {
            details.add(verdictView(v));
        }
        out.put("event_verdicts", details);
        return ResponseEntity.ok(out);
    }

    /** 流式检测运行指标：端到端延迟百分位、各阶段耗时、丢弃计数与空闲 CPU 估计。 */
    @GetMapping("/metrics")
    @RequirePermission("realtime:read")
    public Map<String, Object> metrics() {
        return engine.metrics();
    }

    // ------------------------------ 解析 ------------------------------

    /** 请求体事件对象 → {@link StreamEvent}（数值字段容错解析，缺省为 0）。 */
    private static StreamEvent toEvent(Map<?, ?> m) {
        Map<String, Double> features = new LinkedHashMap<>();
        Object rawFeatures = m.get("features");
        if (rawFeatures instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    features.put(e.getKey().toString(), n.doubleValue());
                }
            }
        }
        return new StreamEvent(
                str(m.get("pteid")),
                str(first(m, "event_type", "eventType")),
                str(m.get("severity")),
                num(m.get("signal")),
                features,
                lng(first(m, "occurred_at_nanos", "occurredAtNanos")),
                0L);
    }

    /** 判定视图（响应体形状稳定，供前端直接渲染）。 */
    private static Map<String, Object> verdictView(StreamVerdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence", v.sequence());
        m.put("pteid", v.pteid());
        m.put("event_type", v.eventType());
        m.put("detected", v.detected());
        m.put("tier", v.tier());
        m.put("risk_score", v.riskScore());
        m.put("rule_score", v.ruleScore());
        m.put("ai_score", v.aiScore());
        m.put("ai_invoked", v.aiInvoked());
        m.put("reasons", v.reasons());
        m.put("latency_ms", v.latencyMs());
        return m;
    }

    private static Map<String, Object> emptyVerdict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detected", false);
        m.put("tier", StreamVerdict.TIER_NONE);
        m.put("risk_score", 0.0);
        m.put("reasons", List.of());
        return m;
    }

    /** 取第一个非空键值。 */
    private static Object first(Map<?, ?> m, String... keys) {
        for (String key : keys) {
            Object v = m.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static long lng(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}