package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminOperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminOperationLogRepository extends JpaRepository<AdminOperationLog, Long> {

    Page<AdminOperationLog> findByOrderByCreatedAtDesc(Pageable pageable);

    Page<AdminOperationLog> findByCreatedAtBetween(Instant start, Instant end, Pageable pageable);

    Page<AdminOperationLog> findByActorAndCreatedAtBetween(String actor, Instant start, Instant end, Pageable pageable);

    Page<AdminOperationLog> findByActionAndCreatedAtBetween(String action, Instant start, Instant end, Pageable pageable);

    /** 异常操作趋势：按动作+日期分组统计失败/写入操作，供异常告警报表。 */
    @Query("select function('date', o.createdAt), count(o) from AdminOperationLog o " +
            "where o.createdAt between :start and :end group by function('date', o.createdAt)")
    List<Object[]> countGroupByDay(@Param("start") Instant start, @Param("end") Instant end);

    /** 失败操作（>=400）占比统计：按状态码分组。 */
    @Query("select o.httpStatus, count(o) from AdminOperationLog o " +
            "where o.createdAt between :start and :end group by o.httpStatus")
    List<Object[]> countGroupByStatus(@Param("start") Instant start, @Param("end") Instant end);

    /** 高频操作人次 TOP，用于异常频率分析。 */
    @Query("select o.action, count(o) from AdminOperationLog o " +
            "where o.createdAt between :start and :end group by o.action order by count(o) desc")
    List<Object[]> countGroupByAction(@Param("start") Instant start, @Param("end") Instant end);
}