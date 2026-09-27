package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.ManagedKey;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.security.KeyManagementService;
import com.potatotv.pacc.service.security.OperatorIdentity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.4 §5 管理端密钥管理端点：托管密钥的列出/新建/轮换/吊销与审计链查看。
 *
 * <p>权限由 {@code AdminKeyFilter} + {@code PermissionInterceptor} 统一覆盖
 * （{@code /api/admin/**}）：读操作需 {@code system:read}，写操作需 {@code system:update}。
 * 操作者身份统一取自 {@link OperatorIdentity#of(HttpServletRequest)}。</p>
 *
 * <p>错误映射（沿用既有 {@code {"error": ...}} 体）：未知用途 → 400；状态冲突 → 409；
 * 密钥不存在 → 404。密钥明文一律不返回，接口只暴露指纹。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/keys")
@RequiredArgsConstructor
public class KeyAdminController {

    private final KeyManagementService keyService;

    /** 托管密钥列表 + 根密钥配置状态 + 状态分布 + 用途清单。 */
    @GetMapping
    @RequirePermission("system:read")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(keyService.list());
    }

    /** 可用的密钥用途清单（供前端下拉）。 */
    @GetMapping("/purposes")
    @RequirePermission("system:read")
    public ResponseEntity<?> purposes() {
        List<String> items = new ArrayList<>();
        for (ManagedKey.Purpose p : ManagedKey.Purpose.values()) {
            items.add(p.name());
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    /** 审计总览：链完整性校验（最多 500 条）+ 全局最近 200 条审计。 */
    @GetMapping("/audit")
    @RequirePermission("system:read")
    public ResponseEntity<?> auditOverview() {
        return ResponseEntity.ok(keyService.auditOverview());
    }

    /** 新建某用途的新版本密钥（同用途旧 ACTIVE 自动降级 ROTATED）。 */
    @PostMapping
    @RequirePermission("system:update")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, String> body, HttpServletRequest req) {
        String actor = OperatorIdentity.of(req);
        try {
            ManagedKey key = keyService.create(body == null ? null : body.get("purpose"),
                    actor, body == null ? null : body.get("note"));
            return ResponseEntity.ok(key);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        } catch (IllegalStateException e) {
            return conflict(e);
        }
    }

    /** 轮换：仅对 ACTIVE 密钥生效，返回新版本密钥。 */
    @PostMapping("/{keyId}/rotate")
    @RequirePermission("system:update")
    public ResponseEntity<?> rotate(@PathVariable String keyId,
                                    @RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest req) {
        String actor = OperatorIdentity.of(req);
        try {
            ManagedKey key = keyService.rotate(keyId, actor, body == null ? null : body.get("note"));
            return ResponseEntity.ok(key);
        } catch (NoSuchElementException e) {
            return notFound(e);
        } catch (IllegalStateException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** 吊销：ACTIVE/ROTATED → REVOKED。 */
    @PostMapping("/{keyId}/revoke")
    @RequirePermission("system:update")
    public ResponseEntity<?> revoke(@PathVariable String keyId,
                                    @RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest req) {
        String actor = OperatorIdentity.of(req);
        try {
            ManagedKey key = keyService.revoke(keyId, actor, body == null ? null : body.get("reason"));
            return ResponseEntity.ok(key);
        } catch (NoSuchElementException e) {
            return notFound(e);
        } catch (IllegalStateException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** 某把密钥的审计明细（最近 200 条）。 */
    @GetMapping("/{keyId}/audit")
    @RequirePermission("system:read")
    public ResponseEntity<?> audit(@PathVariable String keyId) {
        return ResponseEntity.ok(keyService.audit(keyId));
    }

    private static ResponseEntity<?> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    private static ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409).body(Map.of("error", message(e)));
    }

    private static ResponseEntity<?> notFound(Exception e) {
        return ResponseEntity.status(404).body(Map.of("error", message(e)));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? "操作失败" : e.getMessage();
    }
}