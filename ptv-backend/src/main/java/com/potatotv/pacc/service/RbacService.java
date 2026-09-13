package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.AdminPermission;
import com.potatotv.pacc.domain.AdminRole;
import com.potatotv.pacc.domain.AdminRolePermission;
import com.potatotv.pacc.domain.AdminUserRole;
import com.potatotv.pacc.repository.AdminPermissionRepository;
import com.potatotv.pacc.repository.AdminRolePermissionRepository;
import com.potatotv.pacc.repository.AdminRoleRepository;
import com.potatotv.pacc.repository.AdminUserRoleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v5.0 RBAC 三表多对多服务：权限目录 / 角色-权限 / 管理员多角色绑定。
 * <p>权威判定来源为 {@code t_admin_role_permission}；既有的 {@code t_admin_role.permissions}
 * JSON 矩阵作为兼容回退与 UI 矩阵展示。super-admin / api-key 由拦截器直接放行。</p>
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    private final AdminPermissionRepository permissionRepository;
    private final AdminRolePermissionRepository rolePermissionRepository;
    private final AdminRoleRepository roleRepository;
    private final AdminUserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RbacService(AdminPermissionRepository permissionRepository,
                       AdminRolePermissionRepository rolePermissionRepository,
                       AdminRoleRepository roleRepository,
                       AdminUserRoleRepository userRoleRepository) {
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** 权限目录模块清单（与 AdminP0Controller.MODULES 保持一致，用于界面目录展示）。 */
    public static final String[][] MODULES = {
            {"dashboard", "数据大盘"}, {"redscreen", "红屏管理"}, {"inspect", "远程查端"},
            {"accounts", "账号管理"}, {"players", "玩家详情"}, {"records", "作弊记录"},
            {"competition", "赛事风控"}, {"support", "客服工单"}, {"alerts", "告警中心"},
            {"roles", "角色权限"}, {"realtime", "实时监控"}, {"system", "系统管理"},
            {"bi", "BI 报表"}, {"tenant", "多租户"}, {"audit", "审计日志"},
    };

    /** 权限动作清单。 */
    public static final String[][] ACTIONS = {
            {"read", "查看"}, {"create", "新建"}, {"update", "编辑"}, {"delete", "删除"},
    };

    /** 空表时播种权限目录：由模块×动作生成全部权限键。 */
    @Transactional
    public void seedPermissionsIfEmpty() {
        if (permissionRepository.count() > 0) {
            return;
        }
        List<AdminPermission> deps = new ArrayList<>();
        for (String[] mod : MODULES) {
            for (String[] act : ACTIONS) {
                String key = mod[0] + ":" + act[0];
                deps.add(AdminPermission.builder()
                        .id("perm-" + UUID.randomUUID().toString())
                        .permissionKey(key)
                        .module(mod[0])
                        .name(mod[1] + "·" + act[1])
                        .description("模块「" + mod[1] + "」的「" + act[1] + "」操作")
                        .createdAt(Instant.now())
                        .build());
            }
        }
        permissionRepository.saveAll(deps);
        log.info("播种权限目录 {} 条", deps.size());
    }

    /** 将某角色 JSON 矩阵中的 granted 项同步写入 t_admin_role_permission（兼容既有 JSON，三表归一）。 */
    @Transactional
    public void syncRolePermissions(String roleId, String permissionsJson) {
        if (roleId == null || roleId.isBlank()) return;
        Set<String> granted = grantedKeys(permissionsJson);
        // 先清空该角色旧关联，再按当前 granted 集重建，保证三表与 UI 矩阵一致
        rolePermissionRepository.deleteAll(rolePermissionRepository.findByRoleId(roleId));
        List<AdminRolePermission> rows = new ArrayList<>();
        for (String key : granted) {
            String pid = permissionIdOf(key);
            if (pid == null) continue;
            rows.add(AdminRolePermission.builder()
                    .id("rp-" + UUID.randomUUID().toString())
                    .roleId(roleId)
                    .permissionId(pid)
                    .createdAt(Instant.now())
                    .build());
        }
        rolePermissionRepository.saveAll(rows);
    }

    /** 判断 adminActor 是否拥有权限键（含超级/接口密钥由调用方放行）。 */
    public boolean hasPermission(String adminId, String role, String permission) {
        Set<String> perms = permissionSet(adminId, role);
        return perms.contains("*") || perms.contains(permission);
    }

    /**
     * 返回管理员可读权限键集合。
     * <ul>
     *   <li>super-admin / api-key → 全域 {@code *}；</li>
     *   <li>若该管理员绑定多角色 → 合并所有绑定角色的权限键；</li>
     *   <li>无绑定 → 回退请求携带角色的 JSON 矩阵。</li>
     * </ul>
     */
    public Set<String> permissionSet(String adminId, String role) {
        if (role == null || role.isBlank() || "super-admin".equals(role) || "api-key".equals(role)) {
            return Set.of("*");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        // 多角色：管理员绑定角色列表，逐个累积
        List<AdminUserRole> bound = userRoleRepository.findByAdminId(adminId == null ? "" : adminId);
        if (!bound.isEmpty()) {
            List<String> roleKeys = bound.stream().map(AdminUserRole::getRoleId).toList();
            for (String roleKey : roleKeys) {
                out.addAll(rolePermissionKeys(roleKey));
            }
            // 将请求携带的主角色 JSON 矩阵并入（保证新增角色也生效）
            AdminRole current = roleByKey(role);
            if (current != null) {
                out.addAll(grantedKeys(current.getPermissions()));
            }
            return out;
        }
        // 无多角色绑定：回退单角色 JSON 矩阵
        AdminRole r = roleByKey(role);
        if (r != null) {
            return grantedKeys(r.getPermissions());
        }
        return out;
    }

    /* -------------------------------- 内部辅助 -------------------------------- */

    private Set<String> rolePermissionKeys(String roleId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (AdminRolePermission rp : rolePermissionRepository.findByRoleId(roleId)) {
            String key = permissionKey(rp.getPermissionId());
            if (key != null) keys.add(key);
        }
        return keys;
    }

    private AdminPermission permissionOf(String permissionKey) {
        return permissionRepository.findByPermissionKey(permissionKey).orElse(null);
    }

    private String permissionIdOf(String key) {
        AdminPermission p = permissionOf(key);
        if (p != null) return p.getId();
        // 目录缺失时兜底创建（单向添加，幂等由唯一键保证）
        String[] parts = key.split(":", 2);
        String module = parts.length > 0 ? parts[0] : key;
        String action = parts.length > 1 ? parts[1] : "read";
        String name = module + "·" + action;
        AdminPermission p2 = AdminPermission.builder()
                .id("perm-" + UUID.randomUUID().toString())
                .permissionKey(key).module(module).name(name).createdAt(Instant.now())
                .build();
        try {
            return permissionRepository.save(p2).getId();
        } catch (Exception e) {
            AdminPermission existing = permissionOf(key);
            return existing == null ? null : existing.getId();
        }
    }

    private String permissionKey(String permissionId) {
        return permissionRepository.findById(permissionId).map(AdminPermission::getPermissionKey).orElse(null);
    }

    private AdminRole roleByKey(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) return null;
        for (AdminRole r : roleRepository.findAll()) {
            if (roleKey.equalsIgnoreCase(r.getRoleKey())) return r;
        }
        return null;
    }

    /** 从 JSON 矩阵抽取 granted=true 的 module:key 集合。 */
    private Set<String> grantedKeys(String json) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null || json.isBlank()) return out;
        try {
            List<Map<String, Object>> perms = objectMapper.readValue(json, new TypeReference<>() {
            });
            for (Map<String, Object> perm : perms) {
                String module = String.valueOf(perm.get("module"));
                Object actions = perm.get("actions");
                if (!(actions instanceof List<?> list)) continue;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    if (Boolean.TRUE.equals(m.get("granted"))) {
                        out.add(module + ":" + m.get("key"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析角色权限 JSON 失败: {}", e.getMessage());
        }
        return out;
    }
}