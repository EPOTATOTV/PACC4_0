package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminRolePermissionRepository extends JpaRepository<AdminRolePermission, String> {

    List<AdminRolePermission> findByRoleId(String roleId);

    List<AdminRolePermission> findByRoleIdIn(java.util.Collection<String> roleIds);

    void deleteByRoleIdAndPermissionId(String roleId, String permissionId);

    boolean existsByRoleIdAndPermissionId(String roleId, String permissionId);
}