package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DF §4.1.2 联邦聚合模型：云端 FedAvg 产出的全局参数向量（服务端版本，保存在云端）。
 *
 * <p>{@link #modelVersionId} 回指既有 {@link ModelVersion} 登记行（模型类型 {@code FEDERATED}），
 * 因此灰度放量 / 全量上线 / 一键回退继续复用 v5.2 §6.1 的版本状态机，本表只承载联邦特有的
 * 参数向量与收敛指标（{@link #avgLoss}、{@link #totalSamples}）。</p>
 *
 * <p>参数向量以逗号分隔的十进制浮点串存 {@link #weightsJson}，并附 {@link #weightsSha256} 摘要，
 * 便于跨轮次比对与审计。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_federated_model", indexes = {
        @Index(name = "idx_federated_model_round", columnList = "round_id"),
        @Index(name = "idx_federated_model_created", columnList = "created_at")
})
public class FederatedModel {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Column(name = "round_id", nullable = false, length = 64)
    private String roundId;

    /** 联邦模型版本号（单调递增的十进制数字串）。 */
    @Column(nullable = false, length = 16)
    private String version;

    @Builder.Default
    @Column(name = "feature_dim", nullable = false)
    private int featureDim = 0;

    @Builder.Default
    @Column(name = "total_samples", nullable = false)
    private long totalSamples = 0L;

    @Builder.Default
    @Column(name = "avg_loss", nullable = false)
    private double avgLoss = 0.0;

    @Builder.Default
    @Column(name = "weights_sha256", nullable = false, length = 64)
    private String weightsSha256 = "";

    @Builder.Default
    @Column(name = "weights_json", nullable = false, columnDefinition = "TEXT")
    private String weightsJson = "";

    /** 登记的 {@link ModelVersion#getId()}；灰度 / 全量 / 回退走既有机制。 */
    @Column(name = "model_version_id", length = 64)
    private String modelVersionId;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}