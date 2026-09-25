package com.potatotv.pacc.domain.alert;

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
 * §4.3.2 批量通知队列：聚合后的告警组按优先级入队。低优先级延迟到 {@code scheduledAt} 再批量发出，
 * 高优先级立即（PENDING 且 scheduledAt = now）通知。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_alert_notify_queue")
public class AlertNotifyQueue {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_SENT = "SENT";
    public static final String ST_HELD = "HELD";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "group_id", nullable = false, length = 64)
    private String groupId;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String priority = "P2";

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String channel = "dashboard";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = ST_PENDING;

    @Builder.Default
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(length = 2000)
    private String payload;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}