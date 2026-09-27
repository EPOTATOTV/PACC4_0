package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;

/**
 * v5.4 §3.6 客户端安全事件（哈希链审计日志）。
 *
 * <p>服务端不信任客户端上报的安全状态，因此所有事件都写进这条「行内嵌上一行摘要」的链：
 * {@code hash = SHA256(seq|pteid|eventType|level|occurredAt|detail|prevHash)}。
 * 事后删除或修改任意一行都会使其后续行的 prevHash 对不上，{@code SecurityEventService#verifyChain}
 * 即可定位断链位置。单后端实例下 seq 由进程内锁分配，唯一索引是兜底（重复 seq 直接插入失败而不是静默分叉）。</p>
 *
 * <p>{@code eventType} / {@code level} 以字符串落库而非枚举：客户端可能上报未知取值，
 * 落库不允许因此抛异常；入库前统一经 {@link #parseType(String)} / {@link #parseLevel(String)}
 * 归一为受控枚举名，未知值退到安全默认（INTEGRITY_VIOLATION / MEDIUM）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_security_event", indexes = {
        @Index(name = "uk_security_event_seq", columnList = "seq", unique = true),
        @Index(name = "idx_security_event_type", columnList = "event_type, received_at"),
        @Index(name = "idx_security_event_pteid", columnList = "pteid, received_at"),
        @Index(name = "idx_security_event_level", columnList = "level, received_at")
})
public class SecurityEvent {

    /** 事件等级（与现有 IntegrityGuardService 的 CLEAN/SUSPECT/TAMPERED 语义并列，这里是事件级）。 */
    public enum Level {
        INFO,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /** 事件类型白名单；未知上报一律退到 INTEGRITY_VIOLATION。 */
    public enum Type {
        DEBUGGER_DETECTED,
        HOOK_DETECTED,
        INTEGRITY_VIOLATION,
        MEMORY_TAMPER,
        VM_DETECTED,
        SANDBOX_DETECTED,
        FRIDA_DETECTED,
        CHEAT_ENGINE_DETECTED,
        UNAUTHORIZED_ACCESS,
        KEY_ROTATION,
        MODEL_UPDATE,
        CONFIG_CHANGE,
        ATTESTATION_PASS,
        ATTESTATION_FAIL
    }

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    /** 链内单调位置；由服务端分配，绝不接受客户端指定。 */
    @Builder.Default
    @Column(nullable = false)
    private long seq = 0L;

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String pteid = "";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String platform = "";

    @Builder.Default
    @Column(name = "client_ver", nullable = false, length = 32)
    private String clientVer = "";

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(nullable = false, length = 16)
    private String level;

    @Builder.Default
    @Column(nullable = false, length = 512)
    private String detail = "";

    /** 证据（原始报文/堆栈/环境快照），可为空；只作为排查留痕，不参与判定。 */
    @Lob
    @Column(length = Integer.MAX_VALUE)
    private String evidence;

    /** 客户端声称的发生时间（入库前会夹到合理窗口内）。 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 服务端接收时间（聚合/过滤一律以它为准，避免客户端改时间逃逸）。 */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 上一行的 hash；链首为空串。 */
    @Builder.Default
    @Column(name = "prev_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String prevHash = "";

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String hash;

    /**
     * 归一事件类型；未知/空取安全默认 INTEGRITY_VIOLATION。
     * 放在实体上是为了让服务层与单测共用同一套语义。
     */
    public static Type parseType(String raw) {
        if (raw == null) return Type.INTEGRITY_VIOLATION;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (Type t : Type.values()) {
            if (t.name().equals(v)) return t;
        }
        return Type.INTEGRITY_VIOLATION;
    }

    /** 归一事件等级；未知/空取安全默认 MEDIUM。 */
    public static Level parseLevel(String raw) {
        if (raw == null) return Level.MEDIUM;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (Level l : Level.values()) {
            if (l.name().equals(v)) return l;
        }
        return Level.MEDIUM;
    }
}