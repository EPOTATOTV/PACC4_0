package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Broadcast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 赛事直播转播配置仓储。
 */
public interface BroadcastRepository extends JpaRepository<Broadcast, String> {

    /** 管理端全量列表：排序升序，同级按创建倒序。 */
    List<Broadcast> findAllByOrderBySortAscCreatedAtDesc();

    /** 玩家端可见列表：仅对外可见项，排序升序。 */
    List<Broadcast> findByLiveTrueOrderBySortAscCreatedAtDesc();
}