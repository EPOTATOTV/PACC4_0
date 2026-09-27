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
import java.util.Locale;

/**
 * v5.4 §5 密钥审计链（防篡改，形如安全事件日志的哈希链）。
 *
 * <p>为什么需要独立成链：密钥的每一次生命周期迁移（创建/激活/轮换/吊销/过期）都改变系统安全边界，
 * 必须留下不可静默改写的痕迹。每条记录持有序号 {@code seq}、上一条摘要 {@code prevHash} 与自身摘要 {@code hash}，
 * 其中 {@code hash = sha256(seq|keyId|action|fromState|toState|operator|createdAt(ms)|note|prevHash)}，
 * 因此改动任一行内容都会导致其后所有行校验失败，链条断裂位置可被精确定位。</p>
 *
 * <p>{@code seq} 上建有唯一索引，是并发下的数据库级安全网；正常写入由服务端串行分配。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_key_audit_log")
public class KeyAuditLog {

    /** 生命周期动作类型。 */
    public enum Action {
        CREATE,     // 新建
        ACTIVATE,   // 激活
        ROTATE,     // 轮换（旧版本降级）
        REVOKE,     // 吊销
        EXPIRE,     // 过期
        DEACTIVATE  // 停用
    }

    @Id
    @Column(length = 64)
    private String id;

    /** 单调递增链序号（从 1 起），用于校验连续性与顺序。 */
    @Column(nullable = false)
    private long seq;

    @Builder.Default
    @Column(name = "key_id", nullable = false, length = 80)
    private String keyId = "";

    @Builder.Default
    @Column(nullable = false, length = 24)
    private String action = Action.CREATE.name();

    @Builder.Default
    @Column(name = "from_state", nullable = false, length = 16)
    private String fromState = "";

    @Builder.Default
    @Column(name = "to_state", nullable = false, length = 16)
    private String toState = "";

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String operator = "";

    @Builder.Default
    @Column(nullable = false, length = 255)
    private String note = "";

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** 上一条记录的 hash（创世行为空串）。 */
    @Builder.Default
    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash = "";

    /** 本条记录的内容哈希，串联成防篡改链。 */
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String hash = "";

    /**
     * 动作容错解析：未知/空值安全兜底为 {@link Action#CREATE}（读取历史行时避免整体报错）。
     */
    public static Action parseAction(String raw) {
        if (raw == null || raw.isBlank()) return Action.CREATE;
        try {
            return Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Action.CREATE;
        }
    }
}