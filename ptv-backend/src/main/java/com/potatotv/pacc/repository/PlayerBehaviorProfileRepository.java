package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * v5.2 §6.2 玩家行为画像仓库。
 */
public interface PlayerBehaviorProfileRepository extends JpaRepository<PlayerBehaviorProfile, String> {

    /** 不高于某分值的玩家（按分值升序，最危险的在前）。 */
    List<PlayerBehaviorProfile> findByReputationScoreLessThanEqualOrderByReputationScoreAsc(int score);

    /**
     * v5.3 管理端：全量信誉分布聚合。
     * 每行为 {@code [等级, 人数, 平均分, 最低分, 最高分]}，一次查询拿全，避免把全表拉进内存。
     */
    @Query("select p.reputationLevel, count(p), avg(p.reputationScore), min(p.reputationScore), max(p.reputationScore) "
            + "from PlayerBehaviorProfile p group by p.reputationLevel")
    List<Object[]> summaryByLevel();
}