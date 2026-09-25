package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DF §4.1.2 联邦学习客户端更新：设备上传的「模型增量 / 梯度」，<b>原始数据不出设备</b>。
 *
 * <p>本表只存梯度向量本身（{@link #gradientJson}）与校验元数据，不存任何逐事件原始特征或样本。
 * 未通过隐私校验（形状非法 / 非有限值 / 范数超限 / 重复上报）的更新同样登记，但 {@link #accepted}
 * 为 false 且写入 {@link #rejectReason}，保证「拒绝」这一动作可审计。</p>
 *
 * <p>唯一键 {@code (round_id, client_id)} 保证「每客户端每轮至多一条更新」，重复上报直接判重拒绝。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_federated_update", indexes = {
        @Index(name = "idx_federated_update_round", columnList = "round_id")
})
public class FederatedUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false, length = 64)
    private String roundId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    /** FedAvg 的加权权重：该客户端本轮参与训练的样本数。 */
    @Builder.Default
    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Builder.Default
    @Column(name = "feature_dim", nullable = false)
    private int featureDim = 0;

    @Builder.Default
    @Column(name = "gradient_norm", nullable = false)
    private double gradientNorm = 0.0;

    @Builder.Default
    @Column(name = "gradient_hash", nullable = false, length = 64)
    private String gradientHash = "";

    @Builder.Default
    @Column(name = "gradient_json", nullable = false, columnDefinition = "TEXT")
    private String gradientJson = "";

    /** 客户端上报的本轮本地损失；未上报为空。 */
    @Column(name = "client_loss")
    private Double clientLoss;

    @Builder.Default
    @Column(nullable = false)
    private boolean accepted = false;

    @Builder.Default
    @Column(name = "reject_reason", nullable = false, length = 255)
    private String rejectReason = "";

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}