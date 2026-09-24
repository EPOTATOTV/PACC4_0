package com.potatotv.pacc.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 玩家端上报的检测事件。所有数据仅来自玩家本地采集。与游戏服务器无任何关联。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_detection_event")
public class DetectionEvent {

    public enum Edition { BEDROCK, JAVA }

    @Id
    private String id;

    private String pteid;

    /** memory_tamper / process_injection / auto_clicker / java_mod ... */
    private String eventType;

    /** low / medium / high / critical */
    private String severity;

    /** 枚举名存库（BEDROCK / JAVA） */
    @Enumerated(EnumType.STRING)
    private Edition edition;

    /** 端侧预评分 0-100 */
    private int clientRiskScore;

    @Embedded
    private Evidence evidence;

    private String clientVersion;

    private String osInfo;

    private Instant occurredAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Embeddable
    public static class Evidence {
        private String processName;
        private String memoryRegion;
        private String signatureHit;
        private String detailJson;
    }
}