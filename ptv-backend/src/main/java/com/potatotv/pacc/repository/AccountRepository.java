package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    Optional<Account> findByEmail(String email);

    Optional<Account> findByPhone(String phone);

    Optional<Account> findByMcid(String mcid);

    Optional<Account> findByEcid(String ecid);

    Optional<Account> findByQq(String qq);

    Optional<Account> findByResetTokenHash(String resetTokenHash);

    boolean existsByEmail(String email);

    long countByStatus(String status);

    @Modifying
    @Query("update Account a set a.failedLogins = a.failedLogins + 1 where a.pteid = :pteid")
    void incrementFailedLogins(@Param("pteid") String pteid);

    @Modifying
    @Query("update Account a set a.failedLogins = 0 where a.pteid = :pteid")
    void resetFailedLogins(@Param("pteid") String pteid);
}