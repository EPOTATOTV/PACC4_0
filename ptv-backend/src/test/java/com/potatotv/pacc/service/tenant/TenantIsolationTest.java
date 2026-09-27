package com.potatotv.pacc.service.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.tenant.TenantDetectionRecord;
import com.potatotv.pacc.repository.TenantDetectionRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * §4.2.3 租户隔离单测：验收 A25「租户 A 无法访问租户 B 数据」必须真实拒绝，而非静默放行。
 */
class TenantIsolationTest {

    private TenantDetectionRecordRepository recordRepository;
    private TenantQuotaService quotaService;
    private TenantDataService dataService;
    private final TenantGuard guard = new TenantGuard();

    @BeforeEach
    void setUp() {
        recordRepository = mock(TenantDetectionRecordRepository.class);
        quotaService = mock(TenantQuotaService.class);
        dataService = new TenantDataService(recordRepository, quotaService, guard);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void guardRejectsCrossTenantAccess() {
        TenantContext.set(new TenantContext.Principal("admin-a", "tenant-admin", "A", false));

        SecurityException e = assertThrows(SecurityException.class, () -> guard.requireAccess("B"));
        assertTrue(e.getMessage().contains("跨租户"), "应明确拒绝跨租户访问");
        assertEquals("A", guard.requireAccess("A"));
    }

    @Test
    void platformTenantScopeIsEnforced() {
        // 平台身份未指定租户：不得无范围读取租户数据（scopedTenant 拒绝）
        TenantContext.set(new TenantContext.Principal("ops", "super-admin", null, true));
        assertThrows(SecurityException.class, guard::scopedTenant);
        // 显式指定目标租户可访问（该租户存在性已由 TenantContextFilter 按 X-Tenant-Id 校验）
        assertEquals("B", guard.requireAccess("B"));

        // 平台身份已切换到租户 A：不得越权访问 B
        TenantContext.set(new TenantContext.Principal("ops", "super-admin", "A", true));
        assertThrows(SecurityException.class, () -> guard.requireAccess("B"));
        assertEquals("A", guard.requireAccess("A"));
    }

    @Test
    void readOfOtherTenantRecordIsRejected() {
        TenantContext.set(new TenantContext.Principal("admin-a", "tenant-admin", "A", false));
        // 记录实际属于租户 B：带租户 A 的限定查询必然查不到，不得回退为无范围查询
        when(recordRepository.findByIdAndTenantId("rec-b", "A")).thenReturn(Optional.empty());

        assertThrows(SecurityException.class, () -> dataService.get("rec-b"));
        verify(recordRepository).findByIdAndTenantId("rec-b", "A");
    }

    @Test
    void listIsAlwaysScopedToCurrentTenant() {
        TenantContext.set(new TenantContext.Principal("admin-a", "tenant-admin", "A", false));
        when(recordRepository.findByTenantId(eq("A"), any(Pageable.class))).thenReturn(Page.empty());

        assertEquals("A", dataService.list(0, 20).get("tenantId"));
        verify(recordRepository).findByTenantId(eq("A"), any(Pageable.class));
    }

    @Test
    void writeStampsTenantAndMetersUsage() {
        TenantContext.set(new TenantContext.Principal("admin-a", "tenant-admin", "A", false));
        when(recordRepository.save(any(TenantDetectionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantDetectionRecord saved = dataService.record("PTEID0001", "memory_tamper", "high", 88, 2_097_152L);

        assertEquals("A", saved.getTenantId());
        verify(quotaService).checkDetectionQuota("A");
        verify(quotaService).recordDetection("A", 2_097_152L);
        verify(recordRepository, org.mockito.Mockito.never()).findByIdAndTenantId(anyString(), anyString());
    }
}