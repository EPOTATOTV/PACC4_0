package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.df.federated.FederatedLearningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * DF §4.1.2 联邦学习云端端点：开轮 / 收梯度 / 关轮聚合 / 轮次历史 / 当前聚合模型。
 *
 * <p>路径与前端契约一致（{@code /api/admin/df/federated/**}）：本组端点在契约中被归入管理域，
 * 因此沿用 {@code AdminKeyFilter} + {@code @RequirePermission} 的既有保护方式，与其它管理端点完全一致；
 * 写操作标 {@code system:update}，读操作标 {@code system:read}。</p>
 *
 * <p>{@code /updates} 是「设备侧上报梯度」的入口：请求体只含客户端标识、梯度向量与样本数，
 * <b>不含任何原始数据</b>；校验失败一律返回 {@code accepted=false} 与原因，HTTP 层仍是 200，
 * 便于端侧按原因自愈（重复上报、样本数为 0 等）。</p>
 */
@RestController
@RequestMapping("/api/admin/df/federated")
@RequiredArgsConstructor
@SuppressWarnings("null") // 请求体 Map 泛型解析的 null 分析误报
public class DfFederatedController {

    private final FederatedLearningService federatedLearningService;

    /** 开启一轮联邦训练（已有开放轮次则复用并返回 {@code reused=true}）。 */
    @PostMapping("/rounds")
    @RequirePermission("system:update")
    public ResponseEntity<?> openRound(@RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest req) {
        Map<String, Object> m = body == null ? Map.of() : body;
        try {
            return ResponseEntity.ok(federatedLearningService.openRound(
                    intOf(m.get("targetClients")),
                    intOf(m.get("minClients")),
                    intOf(m.get("deadlineSeconds")),
                    doubleOf(m.get("learningRate")),
                    actor(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 上报一个客户端的梯度（模型增量）。
     *
     * <p>请求体：{@code {"roundId": "...", "clientId": "...", "sampleCount": 120,
     * "gradient": [0.1, -0.2, ...], "loss": 0.42}}；{@code roundId} 省略时取当前开放轮次。</p>
     */
    @PostMapping("/updates")
    @RequirePermission("system:update")
    public ResponseEntity<?> submitUpdate(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        String clientId = body.get("clientId") == null ? null : body.get("clientId").toString();
        try {
            return ResponseEntity.ok(federatedLearningService.submitUpdate(
                    body.get("roundId") == null ? null : body.get("roundId").toString(),
                    clientId,
                    gradientOf(body.get("gradient")),
                    intOf(body.get("sampleCount")),
                    doubleOf(body.get("loss"))));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 关闭轮次并执行 FedAvg 聚合，返回聚合模型版本。 */
    @PostMapping("/rounds/{roundId}/close")
    @RequirePermission("system:update")
    public ResponseEntity<?> closeRound(@PathVariable String roundId, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(federatedLearningService.closeRound(roundId, actor(req)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 轮次历史（含每轮损失与收敛差值）——A24「100 轮后损失收敛」的证据面。 */
    @GetMapping("/rounds")
    @RequirePermission("system:read")
    public ResponseEntity<?> rounds(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(Map.of("rounds", federatedLearningService.roundHistory(limit)));
    }

    /** 当前聚合模型（最近一轮产出）；无历史时 404。 */
    @GetMapping("/model/latest")
    @RequirePermission("system:read")
    public ResponseEntity<?> latestModel() {
        Map<String, Object> model = federatedLearningService.latestModel();
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of("error", "尚无联邦聚合模型"));
        }
        return ResponseEntity.ok(model);
    }

    /** 聚合模型历史（供趋势与回退参考）。 */
    @GetMapping("/model/history")
    @RequirePermission("system:read")
    public ResponseEntity<?> modelHistory() {
        return ResponseEntity.ok(Map.of("models", federatedLearningService.modelHistory()));
    }

    // ------------------------------ 解析 ------------------------------
    // 这三个供同包的 DfFederatedPlayerController 复用（玩家侧端点请求体格式完全一致），
    // 故保持包级可见而不是 private。

    /** 梯度数组：兼容 JSON 数组与逗号分隔字符串；非法分量记为 null 交由服务层拒绝。 */
    static List<Double> gradientOf(Object raw) {
        if (raw instanceof List<?> list) {
            List<Double> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(doubleOf(v));
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            List<Double> out = new ArrayList<>();
            for (String part : s.split(",")) {
                out.add(doubleOf(part.trim()));
            }
            return out;
        }
        return null;
    }

    static Integer intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Double doubleOf(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 触发者：由 {@code AdminKeyFilter} 写入的 {@code adminActor}；静态 Key 场景回退为 api-key。 */
    private static String actor(HttpServletRequest req) {
        Object v = req.getAttribute("adminActor");
        return v == null ? "api-key" : v.toString();
    }
}