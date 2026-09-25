package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.v52.ModelTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.2 §6.1 模型版本管理端点：版本列表 / 生效版本 / 灰度 / 全量上线 / 回退 / 手动触发训练。
 * <p>读操作与既有管理端点一致保持只读放行；写操作标注 {@code system:update} 权限键
 * （与既有 DetectorConfigController / AdminP1Controller 的写法一致，super-admin / api-key 直接放行）。</p>
 */
@RestController
@RequestMapping("/api/admin/v52/model")
@RequiredArgsConstructor
public class V52AdminController {

    private final ModelTrainingService modelTrainingService;

    /** 版本列表：{@code modelType} 省略时返回全部模型类型。 */
    @GetMapping("/versions")
    public ResponseEntity<?> versions(@RequestParam(required = false) String modelType) {
        return ResponseEntity.ok(Map.of("versions", modelTrainingService.listVersions(modelType)));
    }

    /** 当前生效版本。 */
    @GetMapping("/active")
    public ResponseEntity<?> active(@RequestParam String modelType) {
        try {
            Map<String, Object> active = modelTrainingService.activeVersion(modelType);
            if (active == null) {
                return ResponseEntity.status(404).body(Map.of("error", "该模型类型没有生效中的版本"));
            }
            return ResponseEntity.ok(active);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设置灰度放量百分比（进入/调整灰度）。 */
    @PostMapping("/{id}/gray")
    @RequirePermission("system:update")
    public ResponseEntity<?> gray(@PathVariable String id, @RequestParam(defaultValue = "10") int percent) {
        try {
            return ResponseEntity.ok(modelTrainingService.versionDetail(
                    modelTrainingService.release(id, percent).getId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 提升为全量生效版本（原 active 落为 rollback 供回退）。 */
    @PostMapping("/{id}/activate")
    @RequirePermission("system:update")
    public ResponseEntity<?> activate(@PathVariable String id) {
        try {
            return ResponseEntity.ok(modelTrainingService.versionDetail(
                    modelTrainingService.promoteToActive(id).getId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 回退到指定版本（该版本重新全量生效，当前 active 落为 rollback）。 */
    @PostMapping("/{id}/rollback")
    @RequirePermission("system:update")
    public ResponseEntity<?> rollback(@PathVariable String id) {
        try {
            return ResponseEntity.ok(modelTrainingService.versionDetail(
                    modelTrainingService.rollbackTo(id).getId()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 手动触发一轮训练：返回各模型指标与登记状态，或跳过原因。 */
    @PostMapping("/train")
    @RequirePermission("system:update")
    public ResponseEntity<?> train() {
        return ResponseEntity.ok(modelTrainingService.trainAndPublish("api-admin"));
    }
}