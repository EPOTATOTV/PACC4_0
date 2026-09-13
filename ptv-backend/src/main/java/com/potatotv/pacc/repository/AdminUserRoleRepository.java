package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminUserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminUserRoleRepository extends JpaRepository<AdminUserRole, String> {

    List<AdminUserRole> findByAdminId(String adminId);

    long countByRoleId(String roleId);

    void deleteByAdminId(String adminId);
}