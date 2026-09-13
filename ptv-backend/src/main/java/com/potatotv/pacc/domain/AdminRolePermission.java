package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 角色-权限关联：一辆车给定角色的细粒度权限键集合（与 t_admin_role.roleKey 对应）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_role_permission")
public class AdminRolePermission {

    @Id
    private String id;

    @Column(name = "role_id", nullable = false, length = 128)
    private String roleId;

    @Column(name = "permission_id", nullable = false, length = 128)
    private String permissionId;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}