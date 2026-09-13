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

/** 管理员-角色多对多：admin_id 为会话身份（飞书 userId 或静态 Key 指纹），可挂多个角色。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_user_role")
public class AdminUserRole {

    @Id
    private String id;

    @Column(name = "admin_id", nullable = false, length = 255)
    private String adminId;

    @Column(name = "role_id", nullable = false, length = 128)
    private String roleId;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}