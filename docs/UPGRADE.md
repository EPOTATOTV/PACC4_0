# PACC v5.4（DF Alpha 1.0.0）升级与回滚指南

面向运维。假设部署形态是仓库自带的 Docker Compose（`docker-compose.yml` + `.env`）。
裸机 + systemd 的部署在文末另附一段。

升级前请确认：

- 已备份数据库（下面第一步就是备份，不要跳过）
- 已确认 `dist/5.4.0/SHA256SUMS.txt` 校验通过（`./scripts/verify-release.sh 5.4.0`）
- 窗口期：v5.4 后端与旧版客户端不兼容，旧端会被下载站的 `min_version` 拦下，需要玩家同步升级

---

## 一、从 v5.2 / v5.3 升级到 v5.4

### 1. 备份数据库

```bash
# 建议先停写入面，避免备份期间数据变动
docker compose stop ptv-backend

docker compose exec -T mysql \
  mysqldump -u root -p"${MYSQL_ROOT_PASSWORD}" --single-transaction --routines \
  "${MYSQL_DATABASE:-pacc}" > "pacc_backup_$(date +%Y%m%d_%H%M%S).sql"

ls -lh pacc_backup_*.sql   # 确认文件非空再继续
```

`--single-transaction` 让 InnoDB 表在备份期间保持一致性快照，不必整库锁表。

### 2. 更新镜像标签

`.env` 或 `docker-compose.yml` 里的镜像标签统一改成 5.4.0。仓库内这些标签已经由
`scripts/bump-version.sh` 统一维护，正常情况下不用手改：

```bash
./scripts/bump-version.sh 5.4.0 --check   # 有输出「不一致」才需要处理
```

### 3. 拉取 / 构建新镜像

```bash
docker compose pull            # 用远程镜像时
# 或本地构建：
docker compose build
```

### 4. 执行数据库迁移

Flyway 在后端启动时自动执行，本次涉及 V26–V34：

| 脚本 | 内容 |
|-|-|
| `V26__dl_release_checksum.sql` | 下载发布件校验和 |
| `V27__effect_config_audit.sql` | 动效配置变更审计 |
| `V28__v52_model_version.sql` | 模型版本 |
| `V29__v52_behavior_profile.sql` | 行为画像 |
| `V30__v52_appeal_auto_review.sql` | 申诉自动复核 |
| `V31__v52_replay_recording.sql` | 查端回放录制 |
| `V32__v54_apm_metrics.sql` | APM 指标 |
| `V33__v54_security_events.sql` | 安全事件哈希链 |
| `V34__v54_key_management.sql` | 密钥管理 |

生产环境 `spring.jpa.hibernate.ddl-auto` 是 `validate`，即实体与表结构对不上后端会直接启动失败——
这是有意的：宁可启动失败，也不要带着不一致的 schema 提供服务。所以这一步报错要当成阻断项处理，
不要改成 `update` 绕过。

### 5. 更新配置

本次新增的配置项都有默认值，**不配置也能启动**。需要调整时按环境变量注入（推荐）或改
`application.yml`。完整清单见下一节。

### 6. 启动并验证

```bash
docker compose up -d
docker compose ps

# 后端健康检查
curl -fsS http://localhost:8080/actuator/health | head -c 200

# 确认迁移已落库
docker compose exec -T mysql \
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE:-pacc}" \
  -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

后端日志里出现 `Started PaccApplication` 且 `flyway_schema_history` 里 V34 的 `success` 为 1，
才算升级成功。

---

## 二、配置变更清单

以下都是 v5.4 新增项，均带默认值。左侧是配置文件里的键，右侧是等价的环境变量。

### APM 性能监控（`pacc.apm.*`）

| 配置键 | 环境变量 | 默认 | 说明 |
|-|-|-|-|
| `pacc.apm.alert-enabled` | `PACC_APM_ALERT_ENABLED` | `true` | 指标越界是否告警 |
| `pacc.apm.raw-retention-days` | `PACC_APM_RAW_RETENTION_DAYS` | `7` | 分钟级原始指标保留天数 |
| `pacc.apm.anomaly-sigma` | `PACC_APM_ANOMALY_SIGMA` | `3.0` | 异常判定 σ 倍数，越小越敏感 |
| `pacc.apm.alert-cooldown-minutes` | `PACC_APM_ALERT_COOLDOWN_MINUTES` | `30` | 同类告警静默窗口 |
| `pacc.apm.alert-webhook-url` | `PACC_APM_ALERT_WEBHOOK_URL` | 空 | 告警外发地址，空则只落库 |
| `pacc.apm.fps-baseline` | `PACC_APM_FPS_BASELINE` | `60` | 帧率基准 |
| `pacc.apm.regression-threshold-percent` | `PACC_APM_REGRESSION_THRESHOLD_PERCENT` | `20` | 版本回归判定阈值 |

### 远程证明（`pacc.attestation.*`）

| 配置键 | 环境变量 | 默认 | 说明 |
|-|-|-|-|
| `pacc.attestation.ttl-ms` | `PACC_ATTESTATION_TTL_MS` | `100000` | 挑战有效期 |
| `pacc.attestation.max-elapsed-ms` | `PACC_ATTESTATION_MAX_ELAPSED_MS` | `500` | 响应最大耗时，超出判异常 |
| `pacc.attestation.require-known-hash` | `PACC_ATTESTATION_REQUIRE_KNOWN_HASH` | `false` | 是否强制命中已知良好哈希库 |

### 密钥管理（`pacc.security.*`）

| 配置键 | 环境变量 | 默认 | 说明 |
|-|-|-|-|
| `pacc.security.key-rotation-days` | `PACC_SECURITY_KEY_ROTATION_DAYS` | `90` | 托管密钥轮换周期 |

### WSS 会话密钥（`pacc.wss.*`）

| 配置键 | 环境变量 | 默认 | 说明 |
|-|-|-|-|
| `pacc.wss.session-key-required` | `PACC_WSS_SESSION_KEY_REQUIRED` | `false` | 是否强制要求会话级动态密钥 |

这一项要**先发客户端、再开开关**。打开后未完成 `session_init` 的客户端二进制信令会被直接拒绝。

### 客户端侧（`pacc-client.properties`）

```properties
pacc.client.wss.uri=wss://pacc.potatotv.asia/ws/ptv
pacc.client.server.uri=https://api.potatotv.asia
pacc.client.wss.session-key.enabled=true
pacc.client.demo=false
pacc.client.demo.login=false
```

### 与设计文档的差异（重要）

设计文档 §2.4.2 列出的键名是规划稿，和实际实现不一致。以下键**在当前代码里不存在**，
不要照抄进配置，否则会被忽略（Spring Boot 不报未知键的错，静默失效最难查）：

| 文档中的键 | 实际键 |
|-|-|
| `pacc.apm.enabled` | 无总开关；用 `pacc.apm.alert-enabled` 控制告警，采集端由客户端开关控制 |
| `pacc.apm.sample-interval` / `pacc.apm.report-interval` | 采集/上报周期在客户端，不在后端配置 |
| `pacc.security.attestation.enabled` | `pacc.attestation.*`（不在 `security` 下） |
| `pacc.security.attestation.timeout-ms` | `pacc.attestation.max-elapsed-ms` |
| `pacc.security.key-management.enabled` | 无；`pacc.security.key-rotation-days` 在位 |
| `pacc.security.key-management.rotation-days` | `pacc.security.key-rotation-days` |
| `pacc.security.anti-debug.mode` | 客户端配置：`pacc.client.*`，由客户端解析 |
| `pacc.security.anti-hook.enabled` | 客户端配置：`pacc.security.antihook` |
| `pacc.security.process-protection.enabled` | 客户端配置：`pacc.security.process-protect` |
| `pacc.security.integrity-check-interval` | 客户端配置，见客户端说明 |

---

## 三、回滚方案

迁移脚本不回退。回滚 = 恢复数据库备份 + 换回旧镜像。

```bash
# 1. 停服务（保留数据卷）
docker compose stop ptv-backend ptv-frontend ai

# 2. 恢复数据库
docker compose exec -T mysql \
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE:-pacc}" < pacc_backup_20260928_120000.sql

# 3. 镜像标签改回 5.3.0
#    .env 里设置 PACC_IMAGE_TAG=5.3.0，或直接改 docker-compose.yml 的 image 行

# 4. 启动
docker compose up -d
curl -fsS http://localhost:8080/actuator/health
```

一键脚本：

```bash
./scripts/upgrade.sh  5.4.0 --dry-run          # 先看要做什么
./scripts/upgrade.sh  5.4.0                    # 升级（含备份 + 健康检查）
./scripts/rollback.sh pacc_backup_20260928_120000.sql 5.3.0
```

回滚注意事项：

- 恢复备份会丢掉备份时间点之后的所有数据（检测记录、申诉、工单）。回滚前把这段时间的数据单独导出。
- 客户端也要跟着回滚，否则新客户端对着旧后端会被拒绝。下载站的 `version.json` 需要同步改回。
- V26–V34 建的表在旧版本里没有对应实体，`ddl-auto=validate` 不会因为多出来的表报错，可以留着。

---

## 四、裸机 + systemd 部署

不用 Docker 时：

```bash
# 停服务
systemctl stop pacc-backend pacc-client

# 备份
mysqldump -u root -p --single-transaction pacc > "pacc_backup_$(date +%Y%m%d).sql"

# 换二进制
cp dist/5.4.0/pacc-backend-5.4.0.jar /opt/pacc/backend/pacc-backend.jar
cp dist/5.4.0/pacc-client-5.4.0.jar  /opt/pacc/client/pacc-client.jar

# 启（Flyway 自动迁移）
systemctl start pacc-backend pacc-client

# 验证
curl -fsS http://localhost:8080/actuator/health
journalctl -u pacc-backend -n 50 --no-pager
```

回滚同理：恢复 SQL 备份，把 jar 换回 5.3.0，重启。