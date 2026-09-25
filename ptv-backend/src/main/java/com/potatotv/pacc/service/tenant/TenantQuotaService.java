package com.potatotv.pacc.service.tenant;

import com.potatotv.pacc.domain.Tenant;
import com.potatotv.pacc.domain.tenant.TenantQuota;
import com.potatotv.pacc.domain.tenant.TenantUsage;
import com.potatotv.pacc.repository.TenantQuotaRepository;
import com.potatotv.pacc.repository.TenantRepository;
import com.potatotv.pacc.repository.TenantUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §4.2.3 资源隔离 + 计费隔离：租户配额（玩家数 / 检测量 / 存储）校验、用量累加与计费计量。
 *
 * <p>写入前 {@link #checkDetectionQuota(String)} 拒绝超限；写入后 {@link #recordDetection(String, long)}
 * 累加用量并追加计量流水（{@code t_tenant_usage}），计费侧按 metric 汇总。</p>
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class TenantQuotaService {

    /** 单价（示例计价策略）：玩家 0.5 元/人，检测 0.001 元/条，存储 0.01 元/MB。 */
    private static final double PRICE_PLAYER = 0.5;
    private static final double PRICE_DETECTION = 0.001;
    private static final double PRICE_STORAGE_MB = 0.01;

    private final TenantQuotaRepository quotaRepository;
    private final TenantUsageRepository usageRepository;
    private final TenantRepository tenantRepository;
    private final TenantGuard guard;

    /** 取租户配额（不存在则按套餐生成默认配额并落库）。 */
    @Transactional
    public TenantQuota quotaOf(String tenantId) {
        return quotaRepository.findById(tenantId).orElseGet(() -> {
            TenantQuota q = defaults(tenantId);
            return quotaRepository.save(q);
        });
    }

    /** 检测量配额校验：已达上限即拒绝。 */
    @Transactional
    public void checkDetectionQuota(String tenantId) {
        TenantQuota q = quotaOf(tenantId);
        if (q.getUsedDetectionVolume() >= q.getMaxDetectionVolume()) {
            throw new IllegalStateException("租户检测量配额已用尽: " + tenantId);
        }
    }

    /** 玩家数配额校验。 */
    @Transactional
    public void checkPlayerQuota(String tenantId) {
        TenantQuota q = quotaOf(tenantId);
        if (q.getUsedPlayers() >= q.getMaxPlayers()) {
            throw new IllegalStateException("租户玩家数配额已用尽: " + tenantId);
        }
    }

    /** 记录一次检测：累加检测量与存储用量，并写入计费计量流水。 */
    @Transactional
    public TenantQuota recordDetection(String tenantId, long storageBytes) {
        TenantQuota q = quotaOf(tenantId);
        q.setUsedDetectionVolume(q.getUsedDetectionVolume() + 1);
        long storageMb = Math.max(0L, (storageBytes + 1_048_575L) / 1_048_576L);
        q.setUsedStorageMb(q.getUsedStorageMb() + storageMb);
        q.setUpdatedAt(Instant.now());
        quotaRepository.save(q);
        meter(tenantId, TenantUsage.METRIC_DETECTION, 1L, PRICE_DETECTION);
        if (storageMb > 0) {
            meter(tenantId, TenantUsage.METRIC_STORAGE, storageMb, PRICE_STORAGE_MB);
        }
        return q;
    }

    /** 记录新增玩家（计费 + 配额用量）。 */
    @Transactional
    public TenantQuota recordPlayer(String tenantId) {
        TenantQuota q = quotaOf(tenantId);
        q.setUsedPlayers(q.getUsedPlayers() + 1);
        q.setUpdatedAt(Instant.now());
        quotaRepository.save(q);
        meter(tenantId, TenantUsage.METRIC_PLAYER, 1L, PRICE_PLAYER);
        return q;
    }

    /** 配额 + 当前用量视图：平台身份可见全部租户，租户身份仅见自身。 */
    @Transactional(readOnly = true)
    public Map<String, Object> quotaView() {
        List<TenantQuota> rows = new ArrayList<>();
        if (guard.isPlatform() && TenantContext.tenantIdOrNull() == null) {
            rows.addAll(quotaRepository.findAll());
        } else {
            rows.add(quotaOf(guard.scopedTenant()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.stream().map(this::quotaMap).toList());
        out.put("total", rows.size());
        return out;
    }

    /** 计费计量流水（指定租户；缺省取当前生效租户）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> usage(String tenantId) {
        String scoped = guard.requireAccess(tenantId == null || tenantId.isBlank() ? null : tenantId);
        List<TenantUsage> rows = usageRepository.findByTenantIdOrderByOccurredAtDesc(scoped);
        double total = rows.stream().mapToDouble(TenantUsage::getAmount).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", scoped);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("totalAmount", round4(total));
        return out;
    }

    private void meter(String tenantId, String metric, long quantity, double unitPrice) {
        usageRepository.save(TenantUsage.builder()
                .tenantId(tenantId)
                .metric(metric)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .amount(round4(quantity * unitPrice))
                .occurredAt(Instant.now())
                .build());
    }

    private TenantQuota defaults(String tenantId) {
        TenantQuota.TenantQuotaBuilder b = TenantQuota.builder().tenantId(tenantId);
        String plan = tenantRepository.findById(tenantId).map(Tenant::getPlan).orElse("FREE");
        switch (plan == null ? "FREE" : plan.toUpperCase()) {
            case "ENTERPRISE" -> b.maxPlayers(10_000).maxDetectionVolume(10_000_000L).maxStorageMb(102_400L);
            case "PRO" -> b.maxPlayers(1_000).maxDetectionVolume(1_000_000L).maxStorageMb(10_240L);
            default -> b.maxPlayers(100).maxDetectionVolume(100_000L).maxStorageMb(1_024L);
        }
        return b.build();
    }

    private Map<String, Object> quotaMap(TenantQuota q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", q.getTenantId());
        m.put("players", Map.of("used", q.getUsedPlayers(), "max", q.getMaxPlayers()));
        m.put("detectionVolume", Map.of("used", q.getUsedDetectionVolume(), "max", q.getMaxDetectionVolume()));
        m.put("storageMb", Map.of("used", q.getUsedStorageMb(), "max", q.getMaxStorageMb()));
        m.put("updatedAt", q.getUpdatedAt());
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}