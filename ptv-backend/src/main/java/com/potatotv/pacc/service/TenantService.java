package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Tenant;
import com.potatotv.pacc.domain.TenantAdmin;
import com.potatotv.pacc.repository.TenantAdminRepository;
import com.potatotv.pacc.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v4.8 平台租户管理：租户分级（FREE/PRO/ENTERPRISE）、状态与功能矩阵、租户管理员绑定。
 * <p>权限模型：平台级身份（super-admin / operator / api-key）可管理全部租户；
 * 租户级身份仅能查看其所属租户。数据隔离由 Open API 密钥的 tenant 维度承载，不强制物理拆表。</p>
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final java.util.Set<String> PLATFORM_ROLES =
            java.util.Set.of("super-admin", "operator", "api-key");

    private final TenantRepository tenantRepository;
    private final TenantAdminRepository tenantAdminRepository;

    /* ---------------- 租户 CRUD ---------------- */

    @Transactional(readOnly = true)
    public Map<String, Object> list(String role, String actor, int page, int size, String search) {
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Tenant> data;
        if (isPlatform(role)) {
            data = (search == null || search.isBlank())
                    ? tenantRepository.findAll(pg)
                    : tenantRepository.findByNameContainingIgnoreCaseOrTenantIdContainingIgnoreCase(search, search, pg);
        } else {
            // 租户级身份：仅返回其绑定并启用的租户
            List<String> ids = tenantAdminRepository.findByAdminIdentity(actor).stream()
                    .filter(TenantAdmin::isEnabled)
                    .map(TenantAdmin::getTenantId)
                    .distinct()
                    .collect(Collectors.toList());
            data = ids.isEmpty() ? Page.empty(pg)
                    : tenantRepository.findByTenantIdIn(ids, pg);
        }
        return Map.of("rows", data.getContent(), "total", data.getTotalElements(),
                "page", data.getNumber(), "total_pages", data.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Tenant get(String role, String actor, String tenantId) {
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        ensureAccess(role, actor, tenantId);
        return t;
    }

    @Transactional
    public Tenant create(String role, String actor, Map<String, Object> body) {
        requirePlatform(role);
        String tenantId = String.valueOf(body.get("tenant_id"));
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("缺少租户标识 tenant_id");
        }
        if (tenantRepository.existsById(tenantId)) {
            throw new IllegalArgumentException("租户标识已存在");
        }
        Tenant t = Tenant.builder()
                .tenantId(tenantId)
                .name(str(body.get("name"), tenantId))
                .plan(str(body.get("plan"), "FREE").toUpperCase())
                .status(str(body.get("status"), "ACTIVE").toUpperCase())
                .maxAdmins(num(body.get("max_admins"), 1))
                .dataRetentionDays(num(body.get("data_retention_days"), 30))
                .apiAccess(bool(body.get("api_access")))
                .webhookAccess(bool(body.get("webhook_access")))
                .customPolicy(bool(body.get("custom_policy")))
                .slaDescription(str(body.get("sla_description"), null))
                .note(str(body.get("note"), null))
                .createdBy(actor)
                .build();
        // 分级功能矩阵联动：免费租户默认关闭高级能力
        applyPlanDefaults(t);
        return tenantRepository.save(t);
    }

    @Transactional
    public Tenant update(String role, String actor, String tenantId, Map<String, Object> body) {
        requirePlatform(role);
        Tenant t = get(role, actor, tenantId);
        if (body.containsKey("name")) t.setName(str(body.get("name"), t.getName()));
        if (body.containsKey("plan")) t.setPlan(str(body.get("plan"), t.getPlan()).toUpperCase());
        if (body.containsKey("status")) t.setStatus(str(body.get("status"), t.getStatus()).toUpperCase());
        if (body.containsKey("max_admins")) t.setMaxAdmins(num(body.get("max_admins"), t.getMaxAdmins()));
        if (body.containsKey("data_retention_days"))
            t.setDataRetentionDays(num(body.get("data_retention_days"), t.getDataRetentionDays()));
        if (body.containsKey("api_access")) t.setApiAccess(bool(body.get("api_access")));
        if (body.containsKey("webhook_access")) t.setWebhookAccess(bool(body.get("webhook_access")));
        if (body.containsKey("custom_policy")) t.setCustomPolicy(bool(body.get("custom_policy")));
        if (body.containsKey("sla_description")) t.setSlaDescription(str(body.get("sla_description"), t.getSlaDescription()));
        if (body.containsKey("note")) t.setNote(str(body.get("note"), t.getNote()));
        return tenantRepository.save(t);
    }

    @Transactional
    public void delete(String role, String actor, String tenantId) {
        requirePlatform(role);
        tenantRepository.deleteById(tenantId);
        // 级联删除租户管理员绑定
        tenantAdminRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .forEach(t -> tenantAdminRepository.deleteById(t.getId()));
    }

    /* ---------------- 租户管理员绑定 ---------------- */

    @Transactional(readOnly = true)
    public List<TenantAdmin> admins(String role, String actor, String tenantId) {
        ensureAccess(role, actor, tenantId);
        return tenantAdminRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public TenantAdmin addAdmin(String role, String actor, String tenantId,
                                String identity, String adminRole) {
        requirePlatform(role);
        Tenant t = tenantRepository.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        if (tenantAdminRepository.countByTenantId(tenantId) >= t.getMaxAdmins()) {
            throw new IllegalArgumentException("已达该租户最大管理员数 " + t.getMaxAdmins());
        }
        if (tenantAdminRepository.findByTenantIdAndAdminIdentity(tenantId, identity).isPresent()) {
            throw new IllegalArgumentException("该管理员已绑定此租户");
        }
        TenantAdmin ta = TenantAdmin.builder()
                .tenantId(tenantId)
                .adminIdentity(identity)
                .role(adminRole == null || adminRole.isBlank() ? "admin" : adminRole)
                .enabled(true)
                .build();
        return tenantAdminRepository.save(ta);
    }

    @Transactional
    public void removeAdmin(String role, String actor, String tenantId, String identity) {
        requirePlatform(role);
        tenantAdminRepository.deleteByTenantIdAndAdminIdentity(tenantId, identity);
    }

    @Transactional
    public void setAdminEnabled(String role, String actor, String tenantId, String identity, boolean enabled) {
        requirePlatform(role);
        tenantAdminRepository.findByTenantIdAndAdminIdentity(tenantId, identity)
                .ifPresent(ta -> {
                    ta.setEnabled(enabled);
                    tenantAdminRepository.save(ta);
                });
    }

    /* ---------------- 分级功能矩阵联动 ---------------- */

    private void applyPlanDefaults(Tenant t) {
        switch (t.getPlan()) {
            case "PRO" -> {
                t.setApiAccess(true);
                t.setWebhookAccess(false);
                t.setCustomPolicy(false);
                if (t.getMaxAdmins() < 3) t.setMaxAdmins(3);
                if (t.getDataRetentionDays() < 90) t.setDataRetentionDays(90);
            }
            case "ENTERPRISE" -> {
                t.setApiAccess(true);
                t.setWebhookAccess(true);
                t.setCustomPolicy(true);
                if (t.getMaxAdmins() < 10) t.setMaxAdmins(10);
                if (t.getDataRetentionDays() < 365) t.setDataRetentionDays(365);
            }
            default -> {
                t.setApiAccess(false);
                t.setWebhookAccess(false);
                t.setCustomPolicy(false);
                if (t.getMaxAdmins() > 1) t.setMaxAdmins(1);
                if (t.getDataRetentionDays() > 30) t.setDataRetentionDays(30);
            }
        }
    }

    /* ---------------- 权限校验 ---------------- */

    private boolean isPlatform(String role) {
        return role != null && PLATFORM_ROLES.contains(role);
    }

    private void requirePlatform(String role) {
        if (!isPlatform(role)) {
            throw new SecurityException("仅平台级管理员可管理租户");
        }
    }

    private void ensureAccess(String role, String actor, String tenantId) {
        if (isPlatform(role)) return;
        boolean bound = tenantAdminRepository
                .findByTenantIdAndAdminIdentity(tenantId, actor).map(TenantAdmin::isEnabled).orElse(false);
        if (!bound) {
            throw new SecurityException("无权访问该租户");
        }
    }

    private static String str(Object v, String dflt) {
        return v == null || String.valueOf(v).isBlank() ? dflt : String.valueOf(v).trim();
    }

    private static int num(Object v, int dflt) {
        if (v == null) return dflt;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static boolean bool(Object v) {
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}