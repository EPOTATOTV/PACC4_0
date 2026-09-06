package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Appeal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AppealRepository extends JpaRepository<Appeal, String> {

    List<Appeal> findByPteidOrderByCreatedAtDesc(String pteid);

    List<Appeal> findByStatusOrderByCreatedAtAsc(String status);

    long countByStatus(String status);

    /** 按阶段+状态分组统计申诉数，供 BI 申诉分析报表使用。 */
    @Query("select a.reviewStage, a.status, count(a) from Appeal a " +
            "where a.createdAt between :start and :end group by a.reviewStage, a.status")
    List<Object[]> countGroupByStageAndStatus(@Param("start") Instant start, @Param("end") Instant end);
}
