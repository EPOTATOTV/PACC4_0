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
 * DF §4.1.2 联邦学习轮次：一轮「开启 → 收集客户端梯度 → 聚合 → 关闭」的生命周期记录。
 *
 * <p>关闭条件（两者取先到）：通过校验的客户端数达到 {@link #targetClients}，或到达 {@link #deadlineAt}
 * 由定时任务关闭。关闭时若通过校验的客户端数不足 {@link #minClients}，本表仍留痕但不产出模型
 * （{@link #modelId} 为空），避免用少量样本生成不可信的全局模型。</p>
 *
 * <p>{@link #avgLoss} 与 {@link #prevAvgLoss} 是 A24「损失收敛」的证据面：前者为本轮加权平均损失，
 * 后者为上一已关闭轮次的同一指标，二者差值即收敛趋势。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_federated_round", indexes = {
        @Index(name = "idx_federated_round_status", columnList = "status"),
        @Index(name = "idx_federated_round_opened", columnList = "opened_at")
})
public class FederatedRound {

    /** 状态：开放中，可接收客户端更新。 */
    public static final String STATUS_OPEN = "OPEN";
    /** 状态：已关闭，不再接收更新。 */
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = STATUS_OPEN;

    /** 期望客户端数：达到即提前关闭。 */
    @Builder.Default
    @Column(name = "target_clients", nullable = false)
    private int targetClients = 3;

    /** 产出聚合模型所需的最少客户端数。 */
    @Builder.Default
    @Column(name = "min_clients", nullable = false)
    private int minClients = 2;

    /** 梯度维度：由首个通过校验的更新确定；为 0 表示尚未确定。 */
    @Builder.Default
    @Column(name = "feature_dim", nullable = false)
    private int featureDim = 0;

    /** 全局模型沿聚合梯度更新的步长。 */
    @Builder.Default
    @Column(name = "learning_rate", nullable = false)
    private double learningRate = 0.1;

    @Builder.Default
    @Column(name = "updates_received", nullable = false)
    private int updatesReceived = 0;

    @Builder.Default
    @Column(name = "updates_accepted", nullable = false)
    private int updatesAccepted = 0;

    @Builder.Default
    @Column(name = "total_samples", nullable = false)
    private long totalSamples = 0L;

    @Builder.Default
    @Column(name = "avg_loss", nullable = false)
    private double avgLoss = 0.0;

    @Builder.Default
    @Column(name = "prev_avg_loss", nullable = false)
    private double prevAvgLoss = 0.0;

    @Builder.Default
    @Column(name = "aggregated_sha256", nullable = false, length = 64)
    private String aggregatedSha256 = "";

    /** 本轮产出的联邦模型 id；未出模型为空。 */
    @Column(name = "model_id", length = 64)
    private String modelId;

    @Builder.Default
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 是否仍可接收更新（开放中且未过截止时间）。 */
    public boolean acceptsUpdates(Instant now) {
        if (!STATUS_OPEN.equals(status)) {
            return false;
        }
        return deadlineAt == null || !now.isAfter(deadlineAt);
    }
}