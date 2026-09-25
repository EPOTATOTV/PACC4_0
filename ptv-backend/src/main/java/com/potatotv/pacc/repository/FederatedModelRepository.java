package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.FederatedModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FederatedModelRepository extends JpaRepository<FederatedModel, String> {

    /** 最近一次聚合产出的模型（当前全局模型）。 */
    Optional<FederatedModel> findFirstByOrderByCreatedAtDesc();

    /** 历史聚合模型（按产出时间倒序）。 */
    List<FederatedModel> findAllByOrderByCreatedAtDesc();
}