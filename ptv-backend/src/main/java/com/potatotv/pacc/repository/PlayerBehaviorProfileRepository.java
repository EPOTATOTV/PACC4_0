package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v5.2 §6.2 玩家行为画像仓库。
 */
public interface PlayerBehaviorProfileRepository extends JpaRepository<PlayerBehaviorProfile, String> {

    /** 不高于某分值的玩家（按分值升序，最危险的在前）。 */
    List<PlayerBehaviorProfile> findByReputationScoreLessThanEqualOrderByReputationScoreAsc(int score);
}