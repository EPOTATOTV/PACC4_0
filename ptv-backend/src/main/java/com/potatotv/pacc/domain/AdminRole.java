package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端角色与权限：permissions 为模块×操作矩阵的 JSON。
 * builtin 角色由控制器空表播种，自定义角色经 /api/admin/roles 增改。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_role")
public class AdminRole {

    @Id
    private String id;

    private String name;

    @Column(name = "role_key", nullable = false, unique = true)
    private String roleKey;

    @Column(length = 4000)
    private String description;

    @Builder.Default
    private boolean builtin = false;

    @Builder.Default
    private Integer memberCount = 0;

    /** 模块×操作权限矩阵 JSON 文本。length 取 int 上限：Hibernate 按它推导列类型，退到 longtext 与迁移脚本一致（默认 255 会推成 tinytext，装不下）。 */
    @Lob
    @Column(length = Integer.MAX_VALUE)
    private String permissions;
}