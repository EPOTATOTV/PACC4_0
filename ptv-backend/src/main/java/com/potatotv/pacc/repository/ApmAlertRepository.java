package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ApmAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * v5.4 APM 告警仓储。
 *
 * <p>只提供管理端需要的那几种访问路径（最近列表 / 按状态 / 按去重键取最近一条）
 * 与冷却判定所需的「同 key 最近的 OPEN 行」；聚合统计在应用层小结果集上做，不写 SQL。</p>
 */
public interface ApmAlertRepository extends JpaRepository<ApmAlert, String> {

    /** 最近的 100 条（不限状态），管理端列表默认口径。 */
    List<ApmAlert> findTop100ByOrderByOccurredAtDesc();

    /** 按状态倒序取（管理端筛选）。 */
    List<ApmAlert> findByStatusOrderByOccurredAtDesc(ApmAlert.Status status);

    /** 状态计数（OPEN 总数用于列表头）。 */
    long countByStatus(ApmAlert.Status status);

    /** 去重键下最近的一条指定状态告警（冷却窗口判定的依据）。 */
    Optional<ApmAlert> findFirstByAlertKeyAndStatusOrderByOccurredAtDesc(String alertKey, ApmAlert.Status status);
}