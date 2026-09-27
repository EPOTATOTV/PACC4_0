package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ManagedKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * v5.4 §5 托管密钥的读写入口。
 * <p>查询方法围绕「按用途找当前版本 / 找历史版本 / 找到期项」组织，避免服务层做全表扫描。</p>
 */
public interface ManagedKeyRepository extends JpaRepository<ManagedKey, String> {

    /** 全量列表（按创建时间倒序，供管理端展示）。 */
    List<ManagedKey> findAllByOrderByCreatedAtDesc();

    /** 某用途的全部版本（版本号倒序，最新在前）。 */
    List<ManagedKey> findByPurposeOrderByVersionDesc(String purpose);

    /** 某用途下处于指定状态的行（用于定位当前 ACTIVE 以便轮换）。 */
    List<ManagedKey> findByPurposeAndState(String purpose, String state);

    Optional<ManagedKey> findByKeyId(String keyId);

    /** 某用途已使用的最大版本号，空表示尚无版本。 */
    @Query("SELECT MAX(k.version) FROM ManagedKey k WHERE k.purpose = :purpose")
    Integer maxVersion(@Param("purpose") String purpose);

    /** 指定状态下已过期（到期时间早于 cutoff）的行，供定时任务批量置为 EXPIRED。 */
    List<ManagedKey> findByStateAndExpiresAtBefore(String state, Instant cutoff);

    long countByState(String state);
}