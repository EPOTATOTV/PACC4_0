package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.8 管理员操作审计：记录管理端敏感写操作（谁/何时/操作了什么/结果/IP）。
 * <p>不记录请求明文（密码/密钥/令牌），仅记录操作维度信息，供合规追溯与异常分析。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_operation_log")
public class AdminOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人（管理员身份）。 */
    @Builder.Default
    @Column(nullable = false, length = 128)
    private String actor = "system";

    /** super-admin / operator / 租户管理员角色。 */
    @Column(length = 32)
    private String role;

    /** 操作动作编码，如 redscreen.unlock / signature.create / config.update。 */
    @Column(nullable = false, length = 64)
    private String action;

    /** 操作对象类型，如 redscreen / signature / account / config / system。 */
    @Column(length = 64)
    private String entityType;

    /** 操作对象 ID（可选）。 */
    @Column(length = 128)
    private String entityId;

    /** 操作详情（脱敏后的摘要 JSON 或说明）。 */
    @Column(length = 4000)
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(length = 8)
    private String httpMethod;

    @Column(length = 255)
    private String httpPath;

    @Column(nullable = false)
    private int httpStatus;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}