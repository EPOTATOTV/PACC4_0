package com.potatotv.pacc.domain;

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
import java.util.UUID;

/**
 * v4.7 客户端崩溃上报：客户端进程异常终止时回传的堆栈与上下文，
 * 供运维定位客户端缺陷 / 反作弊组件加载问题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_client_crash_report", indexes = {
        @Index(name = "idx_crash_created", columnList = "created_at")
})
public class ClientCrashReport {

    public enum Platform { WINDOWS, ANDROID, IOS, HARMONY }

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString().replace("-", "");

    private String pteid;

    @Builder.Default
    private String clientVersion = "";

    @Builder.Default
    private String os = "";

    @Builder.Default
    private String arch = "";

    @Builder.Default
    private Platform platform = Platform.WINDOWS;

    @Lob
    private String stackTrace;

    @Lob
    private String contextJson;

    private String controller;

    @Builder.Default
    private Instant createdAt = Instant.now();
}