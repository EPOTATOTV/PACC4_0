package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Plugin;
import com.potatotv.pacc.domain.PluginComment;
import com.potatotv.pacc.domain.PluginReview;
import com.potatotv.pacc.repository.PluginCommentRepository;
import com.potatotv.pacc.repository.PluginRepository;
import com.potatotv.pacc.repository.PluginReviewRepository;
import com.potatotv.pacc.util.PluginSignature;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v5.0 插件市场：注册 / 审核工作流 / 评分评论 / 下载统计 / 签名校验。
 * <p>开发者提交插件进入 PENDING_REVIEW；管理员审核批准后 PUBLISHED（上架）或打回；
 * 上架插件支持下载计数、评分评论。包内容以 SHA-256 签名留痕防篡改。</p>
 */
@Service
@RequiredArgsConstructor
public class PluginMarketService {

    private final PluginRepository pluginRepo;
    private final PluginReviewRepository reviewRepo;
    private final PluginCommentRepository commentRepo;

    // ---------- 工作流 ----------

    /** 开发者提交插件（DRAFT 或 PENDING_REVIEW）。渲染映射由调用方提供。 */
    public Plugin submit(String name, String description, Plugin.Type type, String author,
                         String pluginVersion, String fileSha256, String packageUrl) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("plugin name is required");
        if (type == null) throw new IllegalArgumentException("plugin type is required");
        if (author == null || author.isBlank()) throw new IllegalArgumentException("plugin author is required");
        if (!PluginSignature.isValidSha256(fileSha256)) throw new IllegalArgumentException("invalid package sha256");
        String id = "plg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Plugin p = Plugin.builder()
                .pluginId(id)
                .name(name.trim())
                .description(description)
                .type(type)
                .author(author)
                .pluginVersion(pluginVersion == null || pluginVersion.isBlank() ? "1.0.0" : pluginVersion)
                .status(Plugin.ST_PENDING)
                .signatureSha256(fileSha256)
                .packageUrl(packageUrl)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return pluginRepo.save(p);
    }

    /** 平台审核：批准上架 / 打回 / 下架。回调发布/下架钩子由调用方处理。 */
    public Plugin review(String pluginId, String reviewer, String action, String comment) {
        Plugin p = pluginRepo.findById(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("plugin not found: " + pluginId));
        String to;
        switch (action == null ? "" : action.toUpperCase()) {
            case "APPROVED" -> to = Plugin.ST_PUBLISHED;
            case "REJECTED" -> to = Plugin.ST_REJECTED;
            case "REMOVED" -> to = Plugin.ST_REMOVED;
            default -> throw new IllegalArgumentException("unsupported review action: " + action);
        }
        reviewRepo.save(PluginReview.builder()
                .pluginId(pluginId).reviewer(reviewer).action(to).comment(comment).createdAt(Instant.now()).build());
        pluginRepo.updateStatus(pluginId, to, "APPROVED".equals(to) ? Instant.now() : null, Instant.now());
        p.setStatus(to);
        p.setPublishedAt("APPROVED".equals(to) ? Instant.now() : null);
        return p;
    }

    /** 上架插件下载计数。 */
    public Map<String, Object> recordDownload(String pluginId) {
        pluginRepo.bumpDownloads(pluginId, 1, Instant.now());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plugin_id", pluginId);
        pluginRepo.findById(pluginId).ifPresent(p -> m.put("downloads", p.getDownloads() + 1));
        return m;
    }

    /** 评分 + 可选评论（同一作者仅保留一条，更新评分时重算累计）。 */
    public PluginComment addComment(String pluginId, String author, int rating, String content) {
        Plugin p = pluginRepo.findById(pluginId)
                .filter(x -> Plugin.ST_PUBLISHED.equals(x.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("plugin not published: " + pluginId));
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("rating must be 1..5");

        PluginComment prior = commentRepo.findByPluginIdAndAuthor(pluginId, author).orElse(null);
        PluginComment saved;
        if (prior == null) {
            saved = commentRepo.save(PluginComment.builder()
                    .pluginId(pluginId).author(author).rating(rating).content(content).createdAt(Instant.now()).build());
            p.setRatingCount(p.getRatingCount() + 1);
            p.setRatingSum(p.getRatingSum() + rating);
        } else {
            int diff = rating - prior.getRating();
            prior.setRating(rating);
            if (content != null) prior.setContent(content);
            saved = commentRepo.save(prior);
            p.setRatingSum(p.getRatingSum() + diff);
        }
        p.setUpdatedAt(Instant.now());
        pluginRepo.save(p);
        return saved;
    }

    // ---------- 查询 ----------

    public Page<Plugin> list(String status, int page, int size) {
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        String st = (status == null || status.isBlank()) ? Plugin.ST_PUBLISHED : status;
        return pluginRepo.findByStatusOrderByUpdatedAtDesc(st, pg);
    }

    public List<PluginReview> reviews(String pluginId) {
        return reviewRepo.findByPluginIdOrderByCreatedAtDesc(pluginId);
    }

    public Page<PluginComment> comments(String pluginId, int page, int size) {
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        return commentRepo.findByPluginIdOrderByCreatedAtDesc(pluginId, pg);
    }
}