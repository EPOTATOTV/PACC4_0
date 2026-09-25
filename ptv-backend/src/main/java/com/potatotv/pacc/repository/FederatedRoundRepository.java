package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.FederatedRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FederatedRoundRepository extends JpaRepository<FederatedRound, String> {

    /** 轮次历史（按开启时间倒序）。 */
    List<FederatedRound> findAllByOrderByOpenedAtDesc();

    /** 指定状态中最近开启的一行（取当前 OPEN 轮次时使用）。 */
    Optional<FederatedRound> findFirstByStatusOrderByOpenedAtDesc(String status);

    /** 上一已关闭轮次（按关闭时间倒序），用于取收敛趋势对照值。 */
    Optional<FederatedRound> findFirstByStatusAndClosedAtIsNotNullOrderByClosedAtDesc(String status);

    /** 已过截止时间但仍开放的轮次（定时任务据此自动关闭）。 */
    List<FederatedRound> findByStatusAndDeadlineAtBefore(String status, Instant now);
}