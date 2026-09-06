package com.potatotv.pacc.service.detection.v47;

import com.potatotv.pacc.domain.DeterPolicy;
import com.potatotv.pacc.repository.DeterPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeterrenceServiceTest {

    private DeterPolicyRepository repo;
    private DeterrenceService service;

    @BeforeEach
    void setup() {
        repo = mock(DeterPolicyRepository.class);
        service = new DeterrenceService(repo);
    }

    private DeterPolicy policy(Long id, String action) {
        return DeterPolicy.builder().id(id).scopeType("FAMILY").scopeValue("CLUSTER_0")
                .action(action).enabled(true).build();
    }

    @Test
    void setPolicyCreatesAndUpdates() {
        when(repo.findByScopeTypeAndScopeValue("FAMILY", "CLUSTER_0")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeterPolicy created = service.setPolicy("FAMILY", "CLUSTER_0", "BLOCK", 5, "确认恶意", "admin");
        assertEquals("FAMILY", created.getScopeType());
        assertEquals("BLOCK", created.getAction());
        assertEquals(5, created.getSeverity());

        // 再次 set 覆盖动作
        when(repo.findByScopeTypeAndScopeValue("FAMILY", "CLUSTER_0")).thenReturn(Optional.of(
                DeterPolicy.builder().id(1L).scopeType("FAMILY").scopeValue("CLUSTER_0")
                        .action("BLOCK").enabled(true).build()));
        DeterPolicy updated = service.setPolicy("FAMILY", "CLUSTER_0", "ISOLATE", null, null, "admin");
        assertEquals("ISOLATE", updated.getAction());
    }

    @Test
    void setPolicyRejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setPolicy("FAMILY", "CLUSTER_0", "PANIC", null, null, "admin"));
    }

    @Test
    void resolveDefaultsToMonitor() {
        when(repo.findByScopeTypeAndScopeValue(anyString(), anyString())).thenReturn(Optional.empty());
        Map<String, Object> r = service.resolve("CLUSTER_9");
        assertEquals("MONITOR", r.get("action"));
        assertEquals(false, r.get("protected"));
    }

    @Test
    void resolveAppliesConfiguredBlock() {
        when(repo.findByScopeTypeAndScopeValue("FAMILY", "CLUSTER_0"))
                .thenReturn(Optional.of(policy(1L, "BLOCK")));
        Map<String, Object> r = service.resolve("CLUSTER_0");
        assertEquals("BLOCK", r.get("action"));
        assertTrue((Boolean) r.get("protected"));
    }

    @Test
    void toggleDisablesPolicy() {
        when(repo.findById(1L)).thenReturn(Optional.of(policy(1L, "BLOCK")));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DeterPolicy p = service.setEnabled(1L, false);
        assertEquals(false, p.isEnabled());
    }

    @Test
    void overviewAggregatesByAction() {
        when(repo.findAllByEnabledTrueOrderByCreatedAtDesc()).thenReturn(List.of());
        when(repo.countByEnabledTrue()).thenReturn(1L);
        when(repo.countByAction("BLOCK")).thenReturn(1L);
        Map<String, Object> o = service.overview();
        assertEquals(1L, o.get("active_count"));
        Map<?, ?> byAction = (Map<?, ?>) o.get("by_action");
        assertEquals(1L, byAction.get("BLOCK"));
        verify(repo).findAllByEnabledTrueOrderByCreatedAtDesc();
    }
}