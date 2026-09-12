package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Broadcast;
import com.potatotv.pacc.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 赛事直播转播管理服务。
 * <p>管理端 CRUD（super-admin / operator / api-key 均可用，与租户平台角色一致）；
 * 玩家端只读由 PlayerP0Controller 直接读取。</p>
 */
@Service
@RequiredArgsConstructor
public class BroadcastService {

    private static final Set<String> PLATFORM_ROLES =
            Set.of("super-admin", "operator", "api-key");

    private final BroadcastRepository broadcastRepository;

    public List<Broadcast> list(String role) {
        requirePlatform(role);
        return broadcastRepository.findAllByOrderBySortAscCreatedAtDesc();
    }

    @Transactional
    public Broadcast create(String role, Map<String, Object> body) {
        requirePlatform(role);
        String title = str(body.get("title"), null);
        String liveId = str(body.get("bilibili_live_id"), null);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("缺少直播标题 title");
        }
        if (liveId == null || liveId.isBlank()) {
            throw new IllegalArgumentException("缺少 B 站直播间号 bilibili_live_id");
        }
        Broadcast b = Broadcast.builder()
                .id(UUID.randomUUID().toString())
                .title(title.trim())
                .bilibiliLiveId(liveId.trim())
                .coverUrl(norm(body.get("cover_url")))
                .description(norm(body.get("description")))
                .platform((String) body.getOrDefault("platform", "bilibili"))
                .live(Boolean.TRUE.equals(body.getOrDefault("live", Boolean.FALSE)))
                .sort(num(body.get("sort"), 0))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return broadcastRepository.save(b);
    }

    @Transactional
    public Broadcast update(String role, String id, Map<String, Object> body) {
        requirePlatform(role);
        Broadcast b = broadcastRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("转播不存在"));
        if (body.containsKey("title")) b.setTitle(str(body.get("title"), b.getTitle()));
        if (body.containsKey("bilibili_live_id")) {
            String liveId = str(body.get("bilibili_live_id"), b.getBilibiliLiveId());
            if (liveId.isBlank()) throw new IllegalArgumentException("直播间号不能为空");
            b.setBilibiliLiveId(liveId.trim());
        }
        if (body.containsKey("cover_url")) b.setCoverUrl(norm(body.get("cover_url")));
        if (body.containsKey("description")) b.setDescription(norm(body.get("description")));
        if (body.containsKey("platform")) b.setPlatform((String) body.get("platform"));
        if (body.containsKey("live")) b.setLive(Boolean.TRUE.equals(body.get("live")));
        if (body.containsKey("sort")) b.setSort(num(body.get("sort"), b.getSort()));
        b.setUpdatedAt(Instant.now());
        return broadcastRepository.save(b);
    }

    /** 仅切换对外可见/开播状态。 */
    @Transactional
    public Broadcast setLive(String role, String id, boolean live) {
        requirePlatform(role);
        Broadcast b = broadcastRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("转播不存在"));
        b.setLive(live);
        b.setUpdatedAt(Instant.now());
        return broadcastRepository.save(b);
    }

    @Transactional
    public void delete(String role, String id) {
        requirePlatform(role);
        Broadcast b = broadcastRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("转播不存在"));
        broadcastRepository.delete(b);
    }

    private boolean isPlatform(String role) {
        return role != null && PLATFORM_ROLES.contains(role);
    }

    private void requirePlatform(String role) {
        if (!isPlatform(role)) {
            throw new SecurityException("仅管理员可管理赛事直播转播");
        }
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v).trim();
    }

    private static String norm(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int num(Object v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}