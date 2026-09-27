-- DF（Deep Fortress）Alpha 1.0.0 / kernel 5.4.0 §4.1.2 联邦学习云端聚合持久化。
--
-- 背景：端侧在本地数据上训练得到「模型增量 / 梯度」，只上传梯度，原始数据不出设备；
-- 云端按样本数加权平均（FedAvg）聚合成新的全局模型，再经既有 t_model_version 灰度机制下发。
-- 本迁移只负责联邦侧的三张表：
--
--   t_federated_round   一轮联邦训练：开启 → 收集客户端更新 → 达到目标客户端数或截止时间后关闭 → 聚合
--   t_federated_update  客户端上报的梯度（仅模型增量，绝不含原始事件数据）；含校验结论与拒绝原因
--   t_federated_model   聚合产出的全局模型版本（服务端参数向量 + 摘要），并回指登记的 t_model_version.id
--
-- 隐私约束（落地层面）：
--   1) 只落梯度向量（gradient_json / weights_json），不落任何逐事件原始特征；
--   2) 未通过校验（形状非法 / 非有限值 / 范数超限 / 重复上报）的更新一律登记 accepted=0 与拒绝原因，供审计；
--   3) 每客户端每轮至多一条更新（唯一键 uk_federated_update_round_client），重复上报直接拒绝。
--
-- 字段口径：gradient_json / weights_json 为逗号分隔的十进制浮点串（后端自解析，避免引入 JSON 依赖）；
--   摘要列一律 VARCHAR(64)（十六进制 SHA-256，不使用 CHAR，避免尾部空格比较问题）。

CREATE TABLE IF NOT EXISTS t_federated_round (
    id                VARCHAR(64)  NOT NULL COMMENT '轮次主键（UUID 去连字符）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
    target_clients    INT          NOT NULL DEFAULT 3 COMMENT '本轮期望客户端数，达到即提前关闭并聚合',
    min_clients       INT          NOT NULL DEFAULT 2 COMMENT '产出聚合模型所需的最少客户端数，不足则关闭但不出模型',
    feature_dim       INT          NOT NULL DEFAULT 0 COMMENT '梯度维度，由首个通过校验的更新确定',
    learning_rate     DOUBLE       NOT NULL DEFAULT 0.1 COMMENT '全局模型沿聚合梯度更新的步长',
    updates_received  INT          NOT NULL DEFAULT 0 COMMENT '收到的客户端更新数（含被拒）',
    updates_accepted  INT          NOT NULL DEFAULT 0 COMMENT '通过校验并计入聚合的更新数',
    total_samples     BIGINT       NOT NULL DEFAULT 0 COMMENT '参与聚合的样本总数（FedAvg 权重之和）',
    avg_loss          DOUBLE       NOT NULL DEFAULT 0 COMMENT '本轮加权平均损失（客户端未上报损失时为 0）',
    prev_avg_loss     DOUBLE       NOT NULL DEFAULT 0 COMMENT '上一已关闭轮次的加权平均损失（收敛趋势对照）',
    aggregated_sha256 VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '聚合得到的全局权重向量摘要（SHA-256）',
    model_id          VARCHAR(64)  NULL COMMENT '本轮产出的联邦模型 id（t_federated_model.id），未出模型为空',
    opened_at         DATETIME(6)  NOT NULL COMMENT '开启时间',
    deadline_at       DATETIME(6)  NULL COMMENT '截止时间，到点后拒绝新更新并由定时任务关闭',
    closed_at         DATETIME(6)  NULL COMMENT '关闭时间（含自动关闭）',
    PRIMARY KEY (id),
    KEY idx_federated_round_status (status),
    KEY idx_federated_round_opened (opened_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_federated_update (
    id             BIGINT       AUTO_INCREMENT NOT NULL,
    round_id       VARCHAR(64)  NOT NULL COMMENT '所属轮次',
    client_id      VARCHAR(128) NOT NULL COMMENT '客户端标识（匿名设备/玩家 id，非账号明文）',
    sample_count   INT          NOT NULL DEFAULT 0 COMMENT '该客户端本轮参与训练的样本数（FedAvg 权重）',
    feature_dim    INT          NOT NULL DEFAULT 0 COMMENT '梯度维度',
    gradient_norm  DOUBLE       NOT NULL DEFAULT 0 COMMENT '梯度 L2 范数（裁剪与离群判定的依据）',
    gradient_hash  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '梯度向量摘要（SHA-256），用于去重与审计',
    gradient_json  TEXT         NOT NULL COMMENT '梯度向量（仅模型增量，绝不含原始事件数据）',
    client_loss    DOUBLE       NULL COMMENT '客户端上报的本轮本地损失，未上报为空',
    accepted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否通过校验并计入聚合',
    reject_reason  VARCHAR(255) NOT NULL DEFAULT '' COMMENT '未通过校验的原因（accepted=0 时非空）',
    created_at     DATETIME(6)  NOT NULL COMMENT '上报时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_federated_update_round_client (round_id, client_id),
    KEY idx_federated_update_round (round_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_federated_model (
    id               VARCHAR(64) NOT NULL COMMENT '联邦聚合模型主键（UUID 去连字符）',
    round_id         VARCHAR(64) NOT NULL COMMENT '产出该模型的轮次',
    version          VARCHAR(16) NOT NULL COMMENT '联邦模型版本号（同类型内单调递增的十进制数字串）',
    feature_dim      INT         NOT NULL DEFAULT 0 COMMENT '全局参数向量维度',
    total_samples    BIGINT      NOT NULL DEFAULT 0 COMMENT '聚合时参与的总样本数',
    avg_loss         DOUBLE      NOT NULL DEFAULT 0 COMMENT '聚合时的加权平均损失',
    weights_sha256   VARCHAR(64) NOT NULL DEFAULT '' COMMENT '全局参数向量摘要（SHA-256）',
    weights_json     TEXT        NOT NULL COMMENT '全局参数向量（服务端聚合结果）',
    model_version_id VARCHAR(64) NULL COMMENT '登记的 t_model_version.id，灰度 / 全量 / 回退走既有机制',
    created_at       DATETIME(6) NOT NULL COMMENT '产出时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_federated_model_version (version),
    KEY idx_federated_model_round (round_id),
    KEY idx_federated_model_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;