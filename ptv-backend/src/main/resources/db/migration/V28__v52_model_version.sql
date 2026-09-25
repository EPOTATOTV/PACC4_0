-- v5.2 §6.1 模型训练流水线：模型版本登记与灰度/上线/回滚状态机。
--
-- 背景：客户端 §2.1 以 .paccm 容器加载本地推理模型，容器布局为
--   [4B magic "PAM1"][2B formatVersion][1B modelType][4B featureDim][4B weightLength]
--   [N B float32 大端权重][32B 前序字节 SHA-256]
-- 后端负责「训练 → 留出集评估 → 登记 → 灰度放量 → 全量上线 → 回滚」的闭环，本表即版本登记处：
--   draft（未达标，仅留痕不发布）→ gray（灰度，gray_percent 为放量百分比）→ active（全量）
-- 历史 active 在切换时落为 rollback，供一键回退到上一个 active 版本。
--
-- file_url 存「相对模型库目录」的文件名而非绝对路径，便于整体搬迁数据目录
--   （模型库目录见 pacc.model.store-dir，默认 <数据目录>/models）。
-- sha256 为模型字节的 SHA-256：既做客户端加载前的完整性校验，也做幂等去重（同字节不重复登记）。
-- signature 为 PTV 私钥（PACC_MODEL_SIGNING_KEY，PKCS#8 Base64）对模型字节的签名 Base64；
--   未配置签名密钥时写入空串并在日志中声明「签名已禁用」，绝不写入伪造签名。

CREATE TABLE IF NOT EXISTS t_model_version (
    id                  VARCHAR(64)  NOT NULL COMMENT '版本主键（UUID 去连字符）',
    model_type          VARCHAR(32)  NOT NULL COMMENT 'XGBOOST / AUTOENCODER / LSTM_AE',
    version             VARCHAR(16)  NOT NULL COMMENT '同模型类型内递增的版本号（十进制数字串）',
    file_url            VARCHAR(512) NOT NULL DEFAULT '' COMMENT '.paccm 文件名（相对模型库目录）',
    sha256              CHAR(64)     NOT NULL DEFAULT '' COMMENT '模型字节 SHA-256（十六进制小写）',
    signature           TEXT         NOT NULL COMMENT '模型字节签名 Base64；签名禁用时为空串',
    accuracy            DOUBLE       NOT NULL DEFAULT 0 COMMENT '留出集准确率',
    false_positive_rate DOUBLE       NOT NULL DEFAULT 0 COMMENT '留出集误报率',
    recall              DOUBLE       NOT NULL DEFAULT 0 COMMENT '留出集召回率',
    training_samples    INT          NOT NULL DEFAULT 0 COMMENT '本次训练使用的已复核样本数',
    status              VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft / gray / active / rollback',
    gray_percent        INT          NOT NULL DEFAULT 0 COMMENT '灰度放量百分比 0-100',
    created_at          DATETIME(6)  NOT NULL,
    published_at        DATETIME(6)  NULL COMMENT '首次进入 gray/active 的时间；draft 为空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_version_type_version (model_type, version),
    KEY idx_model_version_type_status (model_type, status),
    KEY idx_model_version_sha256 (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;