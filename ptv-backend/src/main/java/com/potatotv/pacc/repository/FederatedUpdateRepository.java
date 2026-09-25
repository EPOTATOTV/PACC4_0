package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.FederatedUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FederatedUpdateRepository extends JpaRepository<FederatedUpdate, Long> {

    /** 某轮次的全部更新（含被拒，按上报时间升序），供审计与轮次详情。 */
    List<FederatedUpdate> findByRoundIdOrderByCreatedAtAsc(String roundId);

    /** 某轮次通过校验的更新（按上报时间升序），供 FedAvg 聚合。 */
    List<FederatedUpdate> findByRoundIdAndAcceptedTrueOrderByCreatedAtAsc(String roundId);

    /** 客户端是否已在本轮上报过（唯一键的语义前置校验）。 */
    boolean existsByRoundIdAndClientId(String roundId, String clientId);
}