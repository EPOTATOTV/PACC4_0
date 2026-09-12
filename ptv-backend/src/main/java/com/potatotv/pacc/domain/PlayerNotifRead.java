package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 玩家通知已读标记：记录玩家已读的通知 id，按 (pteid, notif_id) 去重，避免无界增长。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_player_notif_read")
public class PlayerNotifRead {

    @Id
    private String id;

    @Column(name = "pteid", nullable = false)
    private String pteid;

    @Column(name = "notif_id", nullable = false)
    private String notifId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;
}