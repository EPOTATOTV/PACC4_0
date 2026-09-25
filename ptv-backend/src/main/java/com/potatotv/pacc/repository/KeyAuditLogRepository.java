package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.KeyAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * v5.4 §5 密钥审计链的读写入口。
 * <p>{@code findFirstByOrderBySeqDesc} 用于分配下一个链序号（读链尾）；
 * 列表方法均按 {@code seq} 倒序取尾部，避免把整条链拉回内存。</p>
 */
public interface KeyAuditLogRepository extends JpaRepository<KeyAuditLog, String> {

    /** 链尾（seq 最大的记录），用于分配下一序号与前序哈希。 */
    Optional<KeyAuditLog> findFirstByOrderBySeqDesc();

    /** 某把密钥最近 200 条审计。 */
    List<KeyAuditLog> findTop200ByKeyIdOrderBySeqDesc(String keyId);

    /** 全局最近 200 条审计（管理端总览）。 */
    List<KeyAuditLog> findTop200ByOrderBySeqDesc();

    /**
     * 全链按 seq 升序（校验需从创世行起逐条复核，无法只校验尾部片段）。
     * <p>spec 未列出该方法，但链校验必须自创世升序遍历，故补充此只读查询。</p>
     */
    List<KeyAuditLog> findAllByOrderBySeqAsc();
}