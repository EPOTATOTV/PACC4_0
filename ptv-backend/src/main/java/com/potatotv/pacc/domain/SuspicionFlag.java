package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 赛事风控嫌疑：由网关聚合检测产生，含证据哈希链（前条 hash + 本次特征摘要），
 * 供裁判复核形成不干扰篡改的证据链。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_suspicion_flag", indexes = {
        @Index(name = "idx_flag_pteid", columnList = "pteid"),
        @Index(name = "idx_flag_status", columnList = "status")
})
public class SuspicionFlag {

    public enum Kind {
        SHARED_ACCOUNT_MULTI_DEVICE, // 同一账号短期多设备交替
        SHARED_ACCOUNT_MULTI_IP,     // 同一账号短期多 IP 交替（代练/共享）
        DEVICE_FLAPPING,             // 设备指纹异常抖动
        MEDIUM_CONFIDENCE,           // 检测中置信（70-84）：深度观察 + 增强采样，不红屏
        HARDWARE_CHEAT,              // v4.5 硬件级作弊（DMA/宏设备/手柄模拟器）
        TAMPERED_INTEGRITY,          // v4.5 完整性/对抗状态被破坏（签名失效/DSE关闭/TESTSIGNING/代码哈希不符）
        UNKNOWN
    }

    public enum Status { OPEN, REVIEWED, ESB }

    @Id
    private String flagId;

    private String pteid;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String detail;

    /** 严重度权重（0-100）。 */
    private int weight;

    /** 本嫌疑的内容摘要（参与哈希）。 */
    @Column(length = 2048)
    private String evidenceSummary;

    /** 证据链：前一条嫌疑的 chainHash，首条为 genesis。 */
    private String prevChainHash;

    /** 本嫌疑的证据哈希：SHA-256(prevChainHash + canonical(evidenceSummary))。 */
    private String chainHash;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant reviewedAt;

    private String reviewer;

    private String reviewComment;

    /** 该嫌疑是否发生在选手"进行中的对局"期间（公正性优先标记）。 */
    @Builder.Default
    private boolean duringMatch = false;
}