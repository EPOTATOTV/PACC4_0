package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Broadcast;
import com.potatotv.pacc.repository.BroadcastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 赛事直播转播（BroadcastService）单元测试。
 * 用 Mockito mock 仓储，聚焦权限校验与字段规则。
 */
class BroadcastServiceTest {

    private final BroadcastRepository repo = mock(BroadcastRepository.class);
    private final BroadcastService svc = new BroadcastService(repo);

    @BeforeEach
    void stubSaveReturnsInput() {
        // Mockito mock 的 save 默认返回 null，让 create/update/setLive 能拿到保存后的实体
        lenient().when(repo.save(any(Broadcast.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Broadcast sample() {
        return Broadcast.builder()
                .id("b1")
                .title("决赛")
                .bilibiliLiveId("12345")
                .live(false)
                .sort(0)
                .build();
    }

    @Test
    void list_仅管理员可访问() {
        assertThrows(SecurityException.class, () -> svc.list("player"));

        when(repo.findAllByOrderBySortAscCreatedAtDesc()).thenReturn(List.of(sample()));
        var rows = svc.list("super-admin");
        assertEquals(1, rows.size());
        verify(repo).findAllByOrderBySortAscCreatedAtDesc();
    }

    @Test
    void create_合法字段保存() {
        var created = svc.create("operator", Map.of(
                "title", " 决赛 ",
                "bilibili_live_id", " 888 ",
                "sort", 3
        ));
        assertEquals("决赛", created.getTitle());
        assertEquals("888", created.getBilibiliLiveId());
        assertEquals(3, created.getSort());
        assertFalse(created.isLive());
        verify(repo).save(any(Broadcast.class));
    }

    @Test
    void create_缺少标题抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.create("super-admin", Map.of("bilibili_live_id", "888")));
        verify(repo, never()).save(any());
    }

    @Test
    void create_非管理员抛SecurityException() {
        assertThrows(SecurityException.class,
                () -> svc.create("player", Map.of("title", "x", "bilibili_live_id", "1")));
    }

    @Test
    void setLive_更新开关并保存() {
        when(repo.findById("b1")).thenReturn(Optional.of(sample()));
        var updated = svc.setLive("super-admin", "b1", true);
        assertTrue(updated.isLive());
        verify(repo).save(any(Broadcast.class));
    }

    @Test
    void setLive_不存在抛异常() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.setLive("super-admin", "nope", true));
    }

    @Test
    void delete_删除指定id() {
        when(repo.findById("b1")).thenReturn(Optional.of(sample()));
        svc.delete("operator", "b1");
        verify(repo).delete(any(Broadcast.class));
    }
}