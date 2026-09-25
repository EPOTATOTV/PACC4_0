package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * v5.4 §3.6 安全事件（哈希链）仓库。
 *
 * <p>链的读写都走 seq 顺序：追加时取 {@link #findFirstByOrderBySeqDesc()} 的尾巴，
 * 校验/展示时取最近的若干行倒序。</p>
 */
public interface SecurityEventRepository extends JpaRepository<SecurityEvent, String> {

    /** 链尾（seq 最大的一行），用于分配下一个 seq 与 prevHash。 */
    Optional<SecurityEvent> findFirstByOrderBySeqDesc();

    /** 最近 200 条（链上最新端）。 */
    List<SecurityEvent> findTop200ByOrderBySeqDesc();

    /** 按等级过滤的最近 200 条。 */
    List<SecurityEvent> findTop200ByLevelOrderBySeqDesc(String level);

    /** 按事件类型过滤的最近 200 条。 */
    List<SecurityEvent> findTop200ByEventTypeOrderBySeqDesc(String eventType);

    /** 按玩家过滤的最近 200 条。 */
    List<SecurityEvent> findTop200ByPteidOrderBySeqDesc(String pteid);

    /** 时间窗内按类型计数，每行为 {@code [eventType, count]}，量大的排前。 */
    @Query("select e.eventType, count(e) from SecurityEvent e where e.receivedAt >= :from "
            + "group by e.eventType order by count(e) desc")
    List<Object[]> countByTypeSince(@Param("from") Instant from);

    /** 时间窗内按等级计数，每行为 {@code [level, count]}。 */
    @Query("select e.level, count(e) from SecurityEvent e where e.receivedAt >= :from group by e.level")
    List<Object[]> countByLevelSince(@Param("from") Instant from);

    /** 时间窗内事件总数（以服务端接收时间为准）。 */
    long countByReceivedAtAfter(Instant from);
}