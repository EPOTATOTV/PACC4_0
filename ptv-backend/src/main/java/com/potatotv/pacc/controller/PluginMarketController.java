package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Plugin;
import com.potatotv.pacc.service.PluginMarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v5.0 插件市场 REST API（受 X-Admin-Key 保护）。
 * <p>开发者提交插件、平台审核上架/打回/下架、评分评论、下载计数与市场概览。</p>
 */
@RestController
@RequestMapping("/api/admin/plugins")
@RequiredArgsConstructor
public class PluginMarketController {

    private final PluginMarketService market;

    /** 开发者提交插件（进入待审核）。 */
    @PostMapping
    public Map<String, Object> submit(@RequestBody Map<String, String> body) {
        Plugin p = market.submit(
                body.get("name"), body.get("description"),
                toType(body.get("type")),
                body.get("author"), body.get("pluginVersion"),
                body.get("sha256"), body.get("packageUrl"));
        return toView(p);
    }

    /** 平台审核：批准上架 / 打回 / 下架。 */
    @PostMapping("/{pluginId}/review")
    public Map<String, Object> review(@PathVariable String pluginId, @RequestBody Map<String, String> body) {
        Plugin p = market.review(pluginId, body.getOrDefault("reviewer", "system"),
                body.get("action") == null ? "APPROVED" : body.get("action"), body.get("comment"));
        return toView(p);
    }

    /** 插件列表：按状态过滤，默认已上架。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Page<Plugin> src = market.list(status, page, size);
        return Map.of("rows", src.getContent().stream().map(this::toView).toList(),
                "total", src.getTotalElements(), "page", src.getNumber(),
                "total_pages", src.getTotalPages());
    }

    /** 已上架插件下载计数。 */
    @PostMapping("/{pluginId}/download")
    public Map<String, Object> download(@PathVariable("pluginId") String pluginId) {
        return market.recordDownload(pluginId);
    }

    /** 评分 + 评论。 */
    @PostMapping("/{pluginId}/comment")
    public Map<String, Object> comment(@PathVariable String pluginId, @RequestBody Map<String, String> body) {
        int rating = Integer.parseInt(body.getOrDefault("rating", "5"));
        var c = market.addComment(pluginId, body.getOrDefault("author", "anonymous"), rating, body.get("content"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("plugin_id", c.getPluginId());
        m.put("author", c.getAuthor());
        m.put("rating", c.getRating());
        m.put("created_at", c.getCreatedAt());
        return m;
    }

    /** 插件评论列表。 */
    @GetMapping("/{pluginId}/comments")
    public Map<String, Object> comments(@PathVariable("pluginId") String pluginId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        var src = market.comments(pluginId, page, size);
        return Map.of("rows", src.getContent(), "total", src.getTotalElements());
    }

    /** 插件审核留痕。 */
    @GetMapping("/{pluginId}/reviews")
    public Object reviews(@PathVariable("pluginId") String pluginId) {
        return market.reviews(pluginId);
    }

    private Plugin.Type toType(String t) {
        if (t == null) throw new IllegalArgumentException("plugin type is required");
        try {
            return Plugin.Type.valueOf(t.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported plugin type: " + t);
        }
    }

    private Map<String, Object> toView(Plugin p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plugin_id", p.getPluginId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("type", p.getType());
        m.put("author", p.getAuthor());
        m.put("plugin_version", p.getPluginVersion());
        m.put("status", p.getStatus());
        m.put("downloads", p.getDownloads());
        m.put("avg_rating", p.avgRating());
        m.put("rating_count", p.getRatingCount());
        m.put("created_at", p.getCreatedAt());
        m.put("published_at", p.getPublishedAt());
        return m;
    }
}