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

/** 权限定义目录：细粒度权限键（module:operation），供 RBAC 三表多对多判定。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_permission")
public class AdminPermission {

    @Id
    private String id;

    @Column(name = "permission_key", nullable = false, unique = true, length = 128)
    private String permissionKey;

    @Column(nullable = false, length = 64)
    private String module;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 4000)
    private String description;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}