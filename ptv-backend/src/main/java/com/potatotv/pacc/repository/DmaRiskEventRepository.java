package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DmaRiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * DMA/IOMMU 环境巡检事件存储。
 */
public interface DmaRiskEventRepository extends JpaRepository<DmaRiskEvent, String> {

    List<DmaRiskEvent> findTop50ByOrderByCreatedAtDesc();

    List<DmaRiskEvent> findByPteidOrderByCreatedAtDesc(String pteid);
}