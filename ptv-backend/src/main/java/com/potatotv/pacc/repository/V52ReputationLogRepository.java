package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ReputationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v5.2 §7.2 信誉审计查询仓库。
 *
 * <p>v5.2 的审计行仍复用既有 {@link ReputationLog} 表，仅以 {@code source} 的 {@code v52:}
 * 前缀区分口径（历史行为 0-100，v52 行为 0-1000）。这里只补充按来源事件查询的能力
 * （幂等判定与奖励上限统计），不改动既有 {@link ReputationLogRepository}。</p>
 */
public interface V52ReputationLogRepository extends JpaRepository<ReputationLog, String> {

    /** 该来源事件是否已记账（幂等判定：同一事件不重复加减分）。 */
    boolean existsBySource(String source);

    /** 某玩家以给定前缀开头的审计行数（用于「无检测小时」奖励的累计上限统计）。 */
    long countByPteidAndSourceStartingWith(String pteid, String prefix);

    /** 某玩家的 v5.2 审计明细（按时间倒序）。 */
    List<ReputationLog> findByPteidAndSourceStartingWithOrderByCreatedAtDesc(String pteid, String prefix,
                                                                            Pageable pageable);
}