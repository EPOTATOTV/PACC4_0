package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.DeterPolicy;
import com.potatotv.pacc.service.detection.v47.DeterrenceService;
import com.potatotv.pacc.service.detection.v47.FamilyIntelligenceService;
import com.potatotv.pacc.service.detection.v47.IocService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v4.7 威胁情报运营中台管理端点（受 X-Admin-Key 保护）：
 * 家族可视化/谱系、主动威慑分级处置、IOC 中心化。
 */
@RestController
@RequestMapping("/api/admin/v47")
@RequiredArgsConstructor
public class V47AdminController {

    private final FamilyIntelligenceService familyService;
    private final DeterrenceService deterrenceService;
    private final IocService iocService;

    // ---------- 家族可视化 / 谱系 ----------
    /** 家族档案清单。 */
    @GetMapping("/family/overview")
    public Map<String, Object> familyOverview() {
        return Map.of("families", familyService.familyOverview());
    }

    /** 家族谱系（血缘）图。 */
    @GetMapping("/family/graph")
    public Map<String, Object> familyGraph() {
        return familyService.familyGraph();
    }

    // ---------- 主动威慑分级处置 ----------
    @GetMapping("/deter/overview")
    public Map<String, Object> deterOverview() {
        return deterrenceService.overview();
    }

    /** 新增/覆盖某作用域处置策略。 */
    @PostMapping("/deter/set")
    public ResponseEntity<?> setDeter(@RequestBody Map<String, Object> body) {
        try {
            DeterPolicy p = deterrenceService.setPolicy(
                    str(body.get("scopeType")).isBlank() ? "FAMILY" : str(body.get("scopeType")),
                    str(body.get("scopeValue")), str(body.get("action")),
                    body.get("severity") == null ? null : num(body.get("severity")),
                    str(body.get("note")), str(body.get("operator")).isBlank() ? "admin" : str(body.get("operator")));
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 启停策略。 */
    @PostMapping("/deter/{id}/toggle")
    public ResponseEntity<?> toggleDeter(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", true)));
            return ResponseEntity.ok(deterrenceService.setEnabled(id, enabled));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 事件风控联动：给定命中家族解析应执行的处置动作。 */
    @PostMapping("/deter/resolve")
    public Map<String, Object> resolveDeter(@RequestBody Map<String, Object> body) {
        return deterrenceService.resolve(str(body.get("family")));
    }

    // ---------- IOC 中心化 ----------
    @GetMapping("/ioc/overview")
    public Map<String, Object> iocOverview() {
        return iocService.overview();
    }

    /** 检索 IOC（q 模糊 + 类型/状态过滤 + 分页）。 */
    @GetMapping("/ioc/list")
    public Map<String, Object> iocList(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String state,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return iocService.list(q, type, state, page, size);
    }

    /** 从威胁样本抽取指标入库。 */
    @PostMapping("/ioc/import")
    public ResponseEntity<?> importIoc(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(Map.of("imported", iocService.importFromSample(str(body.get("sampleId"))).size()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/ioc/{id}/subscribe")
    public ResponseEntity<?> subscribeIoc(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(iocService.subscribe(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/ioc/{id}/disarm")
    public ResponseEntity<?> disarmIoc(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(iocService.disarm(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 命中计数（检测阈值联动演示）。 */
    @PostMapping("/ioc/{id}/hit")
    public ResponseEntity<?> hitIoc(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(iocService.hit(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 3;
        }
    }
}