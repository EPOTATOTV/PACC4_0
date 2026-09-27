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
 * v5.2 §6.1 模型版本：由训练流水线产出并登记的 {@code .paccm} 模型产物及其灰度状态。
 *
 * <p>状态机：{@code draft}（未达门禁仅留痕）→ {@code gray}（灰度，grayPercent 控制放量）
 * → {@code active}（全量）；被替换下来的 active 落为 {@code rollback}，供一键回退。</p>
 *
 * <p>{@code fileUrl} 存相对模型库目录的文件名，{@code sha256} 既做完整性校验也做幂等去重。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_model_version", indexes = {
        @Index(name = "idx_model_version_type_status", columnList = "model_type,status"),
        @Index(name = "idx_model_version_sha256", columnList = "sha256")
})
public class ModelVersion {

    /** 模型类型：XGBoost 判别模型。 */
    public static final String TYPE_XGBOOST = "XGBOOST";
    /** 模型类型：自编码器异常检测模型。 */
    public static final String TYPE_AUTOENCODER = "AUTOENCODER";

    /** 状态：训练产出但未通过发布门禁，仅留痕。 */
    public static final String STATUS_DRAFT = "draft";
    /** 状态：灰度放量中。 */
    public static final String STATUS_GRAY = "gray";
    /** 状态：全量生效。 */
    public static final String STATUS_ACTIVE = "active";
    /** 状态：曾被全量使用后被替换，可一键回退。 */
    public static final String STATUS_ROLLBACK = "rollback";

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String modelType = TYPE_XGBOOST;

    /** 同模型类型内递增的版本号（十进制数字串，如 "3"）。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String version = "0";

    /** {@code .paccm} 文件名（相对模型库目录）。 */
    @Builder.Default
    @Column(nullable = false, length = 512)
    private String fileUrl = "";

    @Builder.Default
    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String sha256 = "";

    /** 模型字节的签名 Base64；签名密钥未配置时为空串（绝不伪造）。 */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "TEXT")
    private String signature = "";

    @Builder.Default
    @Column(nullable = false)
    private double accuracy = 0.0;

    @Builder.Default
    @Column(nullable = false)
    private double falsePositiveRate = 0.0;

    @Builder.Default
    @Column(nullable = false)
    private double recall = 0.0;

    @Builder.Default
    @Column(nullable = false)
    private int trainingSamples = 0;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    @Builder.Default
    @Column(nullable = false)
    private int grayPercent = 0;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** 首次进入 gray/active 的时间；draft 为空。 */
    private Instant publishedAt;
}