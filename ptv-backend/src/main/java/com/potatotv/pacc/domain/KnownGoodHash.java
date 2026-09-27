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
 * v5.4 §3.6 已知合法摘要注册表。
 *
 * <p>远程证明的信任根：只有登记在册且 active 的摘要才算「已知good」。这样即便端侧被改，
 * 只要代码段摘要变了就无法通过核验；注册表本身在服务端，客户端无从伪造。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_known_good_hash", indexes = {
        @Index(name = "idx_known_hash", columnList = "kind, hash")
})
public class KnownGoodHash {

    /** 摘要种类：代码段 / 配置 / JAR 包。 */
    public enum Kind { CODE_SEGMENT, CONFIG, JAR }

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Kind kind;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String hash;

    /** 下线后仍保留行（审计留痕），只是不再参与核验。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy = "";

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}