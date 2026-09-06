package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.AbExperiment;
import com.potatotv.pacc.service.AbExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A/B 测试管理：列表 / 创建 / 指标采集 / 显著性 / 完成 / 采纳发布。
 */
@RestController
@RequestMapping("/api/admin/ab")
@RequiredArgsConstructor
public class AbExperimentController {

    private static final Set<String> DIMENSIONS =
            Set.of("THRESHOLD", "ALGORITHM", "SCAN", "SIGNATURE", "REDSCREEN", "PERFORMANCE");

    private final AbExperimentService service;

    @GetMapping
    public List<AbExperiment> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            String dimension = body.get("dimension");
            if (dimension == null) {
                throw new IllegalArgumentException("dimension 必填");
            }
            dimension = dimension.toUpperCase();
            if (!DIMENSIONS.contains(dimension)) {
                throw new IllegalArgumentException("dimension 不合法: " + dimension);
            }
            int target = Integer.parseInt(body.get("target_percent"));
            AbExperiment e = service.create(body.get("name"), dimension,
                    body.get("variant_a"), body.get("variant_b"), target, body.get("description"));
            return ResponseEntity.ok(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 显著性视图：返回 ctr_a / ctr_b / lift / p_value / significant。 */
    @GetMapping("/{id}/significance")
    public ResponseEntity<?> significance(@PathVariable String id) {
        AbExperiment e = service.get(id);
        if (e == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.significance(e));
    }

    /** 指标采集：{pteid, exposed, detected, false_positive} 累加到实验 metrics。 */
    @PostMapping("/{id}/breakdown")
    public ResponseEntity<?> breakdown(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            long exposed = toLong(body.get("exposed"));
            long detected = toLong(body.get("detected"));
            long fp = toLong(body.get("false_positive"));
            return ResponseEntity.ok(service.recordBreakdown(id, exposed, detected, fp));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/finish/{id}")
    public ResponseEntity<?> finish(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.finish(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/publish/{id}")
    public ResponseEntity<?> publish(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.publish(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }
}