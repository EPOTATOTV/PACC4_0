package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 特征库特征码。基岩版 / Java 版分别管理，支持灰度发布与版本回滚。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_signature")
public class Signature {

    public enum Edition { BEDROCK, JAVA, GENERIC }

    @Id
    private String id;

    private String name;

    /** 特征码（支持通配符 ??） */
    private String pattern;

    /** 1-5，风险等级 */
    private int riskLevel;

    private Edition edition;

    private String libraryVersion;

    /** DRAFT / PUBLISHED / GRAY / ROLLED_BACK */
    @Builder.Default
    private String state = "DRAFT";

    /** 灰度比例 0-100 */
    private int grayPercent;

    private String createdBy;

    private Instant createdAt;
}