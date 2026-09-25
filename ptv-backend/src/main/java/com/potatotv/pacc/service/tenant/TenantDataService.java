package com.potatotv.pacc.service.tenant;

import com.potatotv.pacc.domain.tenant.TenantDetectionRecord;
import com.potatotv.pacc.repository.TenantDetectionRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §4.2.3 数据隔离执行点：租户独立检测记录的读写一律经租户守卫限定 tenant，
 * 跨租户读取直接拒绝（验收 A25）。
 *
 * <p>写入前做检测量配额校验，写入后累加用量与计费计量。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class TenantDataService {

    private final TenantDetectionRecordRepository recordRepository;
    private final TenantQuotaService quotaService;
    private final TenantGuard guard;

    /** 写入一条租户检测记录（配额校验 → 落库 → 计量）。 */
    @Transactional
    public TenantDetectionRecord record(String pteid, String eventType, String severity,
                                        int riskScore, long storageBytes) {
        String tenant = guard.scopedTenant();
        quotaService.checkDetectionQuota(tenant);
        TenantDetectionRecord record = TenantDetectionRecord.builder()
                .tenantId(tenant)
                .pteid(pteid)
                .eventType(eventType == null ? "unknown" : eventType)
                .severity(severity == null ? "low" : severity)
                .riskScore(riskScore)
                .build();
        TenantDetectionRecord saved = recordRepository.save(record);
        quotaService.recordDetection(tenant, storageBytes);
        return saved;
    }

    /** 当前租户的检测记录分页（强制 tenant 过滤）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> list(int page, int size) {
        String tenant = guard.scopedTenant();
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<TenantDetectionRecord> data = recordRepository.findByTenantId(tenant, pg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant);
        out.put("rows", data.getContent());
        out.put("total", data.getTotalElements());
        out.put("page", data.getNumber());
        out.put("totalPages", data.getTotalPages());
        return out;
    }

    /** 读取单条：必须属于当前租户，否则拒绝（A25）。 */
    @Transactional(readOnly = true)
    public TenantDetectionRecord get(String id) {
        String tenant = guard.scopedTenant();
        return recordRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> new SecurityException("检测记录不存在或跨租户访问被拒绝"));
    }
}