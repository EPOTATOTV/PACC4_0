package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * v5.2 §6.2 玩家作息画像（DailyPattern）：按「日 + 小时」聚合的在线时长与事件量。
 *
 * <p>同一玩家同一天同一小时唯一（uk_daily_pattern），观测时增量累加。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_player_daily_pattern", indexes = {
        @Index(name = "uk_daily_pattern", columnList = "pteid,pattern_date,hour_of_day", unique = true),
        @Index(name = "idx_daily_pattern_date", columnList = "pattern_date")
})
public class PlayerDailyPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "pteid", nullable = false, length = 64)
    private String pteid = "";

    /** 统计日。 */
    @Builder.Default
    @Column(name = "pattern_date", nullable = false)
    private LocalDate patternDate = LocalDate.now();

    /** 小时 0-23。 */
    @Builder.Default
    @Column(name = "hour_of_day", nullable = false)
    private int hourOfDay = 0;

    /** 该时段累计在线秒数。 */
    @Builder.Default
    @Column(name = "session_seconds", nullable = false)
    private long sessionSeconds = 0L;

    /** 该时段累计观测 / 检测事件数。 */
    @Builder.Default
    @Column(name = "event_count", nullable = false)
    private long eventCount = 0L;
}