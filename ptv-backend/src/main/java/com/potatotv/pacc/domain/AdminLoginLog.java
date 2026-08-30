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
 * 管理员登录日志：记录登录方式（key / feishu）、身份、角色、结果与来源 IP。
 * <p>仅用于审计，不落任何密码/密钥/令牌明文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_admin_login_log")
public class AdminLoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录账号标识（密钥模式为模糊处理后的 Key 指纹；飞书模式为企业邮箱/用户 ID）。 */
    @Column(length = 128)
    private String identity;

    /** key / feishu */
    @Column(length = 16, nullable = false)
    private String method;

    /** super-admin / operator */
    @Column(length = 32)
    private String role;

    /** success / fail */
    @Column(length = 16, nullable = false)
    private String result;

    @Column(length = 64)
    private String ip;

    @Builder.Default
    private Instant createdAt = Instant.now();
}