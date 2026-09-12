package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminRoleRepository extends JpaRepository<AdminRole, String> {

    List<AdminRole> findAllByOrderByBuiltinDesc();

    Optional<AdminRole> findByRoleKey(String roleKey);
}