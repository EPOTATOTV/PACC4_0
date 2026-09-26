package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.ExportTask;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.EffectivenessService;
import com.potatotv.pacc.service.ExportService;
import com.potatotv.pacc.service.ListService;
import com.potatotv.pacc.service.ReleaseService;
import com.potatotv.pacc.service.ReputationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * v5.0 P1 管理端点：黑白名单 / 版本发布 / 信誉明细 / 数据导出 / 反作弊效果。
 * 各写操作按业务标注所需权限键，读操作按需半开放。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminP1Controller {

    private final ListService listService;
    private final ReleaseService releaseService;
    private final ReputationService reputationService;
    private final ExportService exportService;
    private final EffectivenessService effectivenessService;

    // -------------------------------- 黑白名单 --------------------------------

    @GetMapping("/lists")
    public ResponseEntity<?> lists(@RequestParam(required = false) String list_type,
                                   @RequestParam(required = false) String entry_type,
                                   @RequestParam(required = false) String status) {
        return ResponseEntity.ok(Map.of(
                "list_types", List.of(ListService.LIST_TYPES),
                "entry_types", List.of(ListService.ENTRY_TYPES),
                "entries", listService.list(list_type, entry_type, status)));
    }

    @PostMapping("/lists")
    @RequirePermission("players:update")
    public ResponseEntity<?> addListEntry(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String operator = actor(req);
        try {
            Map<String, Object> m = body;
            return ResponseEntity.ok(listService.add(
                    str(m.get("list_type")), str(m.get("entry_type")), str(m.get("value")),
                    str(m.get("reason")), str(m.get("status")), operator,
                    m.get("expires_at_ms") == null ? null : ((Number) m.get("expires_at_ms")).longValue()).getId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/lists/{id}")
    @RequirePermission("players:update")
    public ResponseEntity<?> removeListEntry(@PathVariable String id) {
        return listService.remove(id)
                ? ResponseEntity.ok(Map.of("removed", true))
                : ResponseEntity.status(404).body(Map.of("error", "条目不存在"));
    }

    /** 命中判定：名单方向+对象类型+值是否命中有效条目。 */
    @GetMapping("/lists/match")
    public ResponseEntity<?> listMatch(@RequestParam String list_type,
                                       @RequestParam String entry_type,
                                       @RequestParam String value) {
        return ResponseEntity.ok(Map.of("match", listService.matches(list_type, entry_type, value)));
    }

    // -------------------------------- 版本发布 --------------------------------

    @GetMapping("/releases")
    public ResponseEntity<?> releases(@RequestParam(required = false) String platform,
                                      @RequestParam(required = false) String channel) {
        return ResponseEntity.ok(Map.of("releases", releaseService.list(platform, channel)));
    }

    @PostMapping("/releases")
    @RequirePermission("system:create")
    public ResponseEntity<?> createRelease(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        int buildNo;
        try {
            buildNo = body.get("build_no") == null ? 0 : ((Number) body.get("build_no")).intValue();
        } catch (Exception e) {
            buildNo = 0;
        }
        return ResponseEntity.ok(releaseService.create(
                str(body.get("platform")), str(body.get("channel")), str(body.get("version")),
                buildNo, str(body.get("notes")), str(body.get("download_url")),
                str(body.get("sha256")), actor(req), body));
    }

    @PutMapping("/releases/{id}")
    @RequirePermission("system:update")
    public ResponseEntity<?> updateRelease(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var r = releaseService.update(id, body);
        return r == null ? ResponseEntity.status(404).body(Map.of("error", "发布记录不存在")) : ResponseEntity.ok(r.getId());
    }

    @PostMapping("/releases/{id}/publish")
    @RequirePermission("system:create")
    public ResponseEntity<?> publishRelease(@PathVariable String id, HttpServletRequest req) {
        var r = releaseService.publish(id, actor(req));
        return r == null ? ResponseEntity.status(404).body(Map.of("error", "发布记录不存在")) : ResponseEntity.ok(r.getId());
    }

    @PostMapping("/releases/{id}/archive")
    @RequirePermission("system:update")
    public ResponseEntity<?> archiveRelease(@PathVariable String id) {
        return releaseService.archive(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(404).body(Map.of("error", "发布记录不存在"));
    }

    @DeleteMapping("/releases/{id}")
    @RequirePermission("system:delete")
    public ResponseEntity<?> deleteRelease(@PathVariable String id) {
        boolean removed = releaseService.remove(id);
        if (!removed) {
            return ResponseEntity.status(400).body(Map.of("error", "仅草稿/已归档记录可删除"));
        }
        return ResponseEntity.ok(Map.of("removed", true));
    }

    // -------------------------------- 信誉明细 --------------------------------

    @GetMapping("/reputation/{pteid}")
    public ResponseEntity<?> reputation(@PathVariable String pteid,
                                        @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(reputationService.adminDetail(pteid, limit));
    }

    // -------------------------------- 数据导出 --------------------------------

    @PostMapping("/export")
    @RequirePermission("bi:create")
    public ResponseEntity<?> submitExport(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ExportTask task = exportService.submit(str(body.get("subject")), str(body.get("filters")), actor(req));
        return ResponseEntity.ok(Map.of("task_id", task.getId(), "status", task.getStatus()));
    }

    @GetMapping("/export")
    public ResponseEntity<?> listExports(HttpServletRequest req) {
        return ResponseEntity.ok(Map.of("tasks", exportService.list(actor(req))));
    }

    @GetMapping("/export/{taskId}/download")
    public ResponseEntity<?> downloadExport(@PathVariable String taskId, @RequestParam String key) {
        ExportTask t = exportService.consumeDownload(taskId, key);
        if (t == null) {
            return ResponseEntity.status(404).body(Map.of("error", "任务未就绪或下载凭证无效"));
        }
        File f = new File(t.getFilePath());
        if (!f.exists() || !Files.isReadable(f.toPath())) {
            return ResponseEntity.status(404).body(Map.of("error", "文件不存在或已被清理"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + taskId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(new FileSystemResource(f));
    }

    // -------------------------------- 反作弊效果 --------------------------------

    @GetMapping("/effectiveness")
    public ResponseEntity<?> effectiveness() {
        return ResponseEntity.ok(effectivenessService.summary());
    }

    @GetMapping("/effectiveness/cheat-types")
    public ResponseEntity<?> cheatTypes() {
        return ResponseEntity.ok(Map.of("cheat_types", effectivenessService.cheatTypeDistribution()));
    }

    private static String actor(HttpServletRequest req) {
        return req.getAttribute("adminActor") == null ? "api-key" : req.getAttribute("adminActor").toString();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}