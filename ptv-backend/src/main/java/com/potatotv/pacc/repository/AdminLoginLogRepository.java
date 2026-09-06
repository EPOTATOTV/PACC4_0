package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.AdminLoginLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminLoginLogRepository extends JpaRepository<AdminLoginLog, Long> {
    List<AdminLoginLog> findTop50ByOrderByCreatedAtDesc();

    /** 按日期+结果分组统计登录，供 BI 登录审计报表使用。 */
    @Query("select function('date', l.createdAt), l.result, count(l) from AdminLoginLog l " +
            "where l.createdAt between :start and :end group by function('date', l.createdAt), l.result")
    List<Object[]> countGroupByDayAndResult(@Param("start") Instant start, @Param("end") Instant end);
}