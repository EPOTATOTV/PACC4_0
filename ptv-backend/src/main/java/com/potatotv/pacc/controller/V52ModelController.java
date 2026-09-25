package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.ModelVersion;
import com.potatotv.pacc.service.detection.v52.ModelTrainingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.2 §2.1.3 端侧模型下发端点：清单（版本 + SHA-256 + 签名）与产物下载。
 *
 * <p>灰度放量在服务端完成分桶（{@link ModelTrainingService#releasesFor(String)}）：玩家只会拿到
 * 「命中灰度」或「全量生效」的版本，客户端不需要知道灰度比例。产物按登记 SHA-256 校验后才下发，
 * 下载响应附 {@code X-PACC-Model-Version} / {@code X-PACC-Model-SHA256} 供端侧二次比对。</p>
 *
 * <p>鉴权沿用玩家会话约定：{@code JwtAuthFilter} 对 {@code /api/player/**} 无令牌直接 401，
 * 通过后把 PTEID 写入请求属性；此处仍显式判空，避免过滤器链路变更时静默放行。</p>
 */
@RestController
@RequestMapping("/api/player/v52/model")
@RequiredArgsConstructor
public class V52ModelController {

    private final ModelTrainingService modelTrainingService;

    /** 可下发模型清单：每个类型至多一条（灰度版本优先于全量版本）。 */
    @GetMapping("/manifest")
    public ResponseEntity<?> manifest(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (ModelVersion row : modelTrainingService.releasesFor(pteid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model_type", row.getModelType());
            m.put("version", row.getVersion());
            m.put("sha256", row.getSha256());
            m.put("signature", row.getSignature());
            m.put("status", row.getStatus());
            m.put("url", "/api/player/v52/model/" + row.getModelType() + "/file");
            models.add(m);
        }
        return ResponseEntity.ok(Map.of("models", models));
    }

    /** 下载当前玩家应加载的指定类型模型产物。 */
    @GetMapping("/{modelType}/file")
    public ResponseEntity<?> file(@PathVariable String modelType, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String type = modelType == null ? "" : modelType.trim().toUpperCase(Locale.ROOT);
        ModelVersion row = modelTrainingService.releasesFor(pteid).stream()
                .filter(r -> r.getModelType().equals(type))
                .findFirst()
                .orElse(null);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of("error", "该模型类型没有下发给当前玩家的版本"));
        }
        try {
            byte[] raw = modelTrainingService.readArtifact(row);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + safeName(row) + "\"")
                    .header("X-PACC-Model-Version", row.getVersion())
                    .header("X-PACC-Model-SHA256", row.getSha256())
                    .cacheControl(CacheControl.noStore())
                    .body(raw);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 下载文件名：类型 + 版本都由流水线生成，这里再做一次字符白名单，杜绝响应头注入。 */
    private static String safeName(ModelVersion row) {
        String type = row.getModelType().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        String version = row.getVersion().replaceAll("[^0-9A-Za-z._-]", "_");
        return type + "-v" + version + ".paccm";
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }
}