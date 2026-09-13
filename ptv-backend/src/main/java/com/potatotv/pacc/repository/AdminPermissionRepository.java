package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminPermissionRepository extends JpaRepository<AdminPermission, String> {

    Optional<AdminPermission> findByPermissionKey(String permissionKey);

    List<AdminPermission> findAllByOrderByModuleAscPermissionKeyAsc();
}