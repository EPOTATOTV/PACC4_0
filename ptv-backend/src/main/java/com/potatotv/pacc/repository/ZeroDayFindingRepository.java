package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ZeroDayFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ZeroDayFindingRepository extends JpaRepository<ZeroDayFinding, String> {

    List<ZeroDayFinding> findByStatusOrderByCreatedAtDesc(ZeroDayFinding.Status status);

    List<ZeroDayFinding> findTop50ByOrderByCreatedAtDesc();

    /**
     * v5.2 §6.1 模型训练的数据来源：某时间窗内已人工复核（有 confirmed 结论）的零日发现。
     * 复核结论由 {@code ActiveLearningService.reviewFinding} 写入 reviewed_at / confirmed。
     */
    List<ZeroDayFinding> findByStatusAndReviewedAtAfterOrderByReviewedAtDesc(ZeroDayFinding.Status status, Instant after);

    long countByStatus(ZeroDayFinding.Status status);
}