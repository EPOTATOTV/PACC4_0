package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PlayerDailyPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * v5.2 §6.2 玩家作息画像仓库。
 */
public interface PlayerDailyPatternRepository extends JpaRepository<PlayerDailyPattern, Long> {

    /** 某玩家某日某小时的聚合行（增量累加的定位键）。 */
    Optional<PlayerDailyPattern> findByPteidAndPatternDateAndHourOfDay(String pteid, LocalDate date, int hour);

    /** 某玩家的作息明细（按日期倒序、同日按小时升序）。 */
    List<PlayerDailyPattern> findByPteidOrderByPatternDateDescHourOfDayAsc(String pteid);

    /** 清理某日期之前的明细（夜间任务压缩历史）。 */
    long deleteByPatternDateBefore(LocalDate date);
}