# PACC v4.0 发布前最终检查清单

> 生成时间：2026-08-30。用于发布 v4.0 前的上线安检。逐项勾选（`[x]` 表示已确认）。

## A. 安全基线（Fail-Closed）

- [x] `PACC_ADMIN_API_KEY` ≥12 字节且为强随机（当前 64 字节）
- [x] `PACC_SECURITY_SUPER_ADMIN_KEY` ≥12 字节且为强随机（当前 64 字节）
- [x] `PACC_SECURITY_JWT_SECRET` ≥32 字节且为强随机（当前 128 字节）
- [x] `PACC_SECURITY_WSS_SIGN_SECRET` ≥16 字节且为强随机（当前 64 字节）
- [x] `.env` 已被 `.gitignore` 忽略（`git check-ignore .env` 通过）
- [ ] 若密钥曾在 git 历史/公网泄露，发布前重置密钥
- [x] 启动守卫 fail-closed：缺任一密钥/SMTP/飞书配置即拒绝启动（`StartupSecretGuard`）
- [ ] 玩家端 `PACC_CLIENT_WSS_SECRET` 与 `PACC_SECURITY_WSS_SIGN_SECRET` 一致（否则 WSS 上报校验失败）
  - 代码侧：后端 `.env` 已填 `PACC_SECURITY_WSS_SIGN_SECRET`（当前 64 字节）；玩家客户端 `PACC_CLIENT_WSS_SECRET` 需在部署时用同一值，代码内无法自动校验，属部署约定

## B. 会话 / Cookie 安全

- [x] 玩家 token 仅存 `pacc_player` HttpOnly Cookie（前端无 localStorage）
- [x] 管理后台 token 仅存 `pacc_admin` HttpOnly Cookie（前端无 localStorage）
- [x] Cookie 带 `SameSite=Lax`，生产附加 `Secure`
- [x] 管理会话指纹绑定（IP+UA）生效，跨设备令牌被拒
- [x] 登出清 Cookie（`/api/admin/logout`）
- [x] 前端登录态由 `/api/admin/me` 探测，不读本地存储

## C. 认证与 SSO

- [ ] **飞书 SSO**（`PACC_FEISHU_ENABLED=true`）：
  - [ ] 飞书后台「重定向 URL」已配为 `https://admin.potatotv.asia/api/admin/feishu/oauth/callback`
  - [ ] 应用已开通通讯录读取权限（否则邮箱/手机号白名单拿不到，只能按 open_id 匹配）
  - [ ] `PACC_FEISHU_SUPER_ADMIN_USERIDS / ADMIN_USERIDS` 白名单已填
  - [ ] `state` 防 CSRF 生效
- [ ] **SMTP**：生产 `PACC_MAIL_STUB_ENABLED=false`，并填 `SMTP_HOST/PORT/USERNAME/PASSWORD/FROM`
  - ⚠️ 当前 `.env` 为 `true`（stub），发布前必须置 false 并配真 SMTP，否则密码找回不发送
- [x] 飞书回调地址与前端反代一致（`admin` 域 → `/api` → 后端）

## D. 部署与健康

- [x] `docker compose up -d --build` 全栈构建成功（5 镜像 Built）
- [x] 全部容器 healthy：mysql / backend / frontend / gateway-go / ai
- [x] `GET /actuator/health` 返回 `{"status":"UP"}`
- [x] frontend `/healthz` 返回 200（已修 IPv6→127.0.0.1 问题）
- [x] gateway 四子域路由正确：`admin` / `api` / `pacc` / `dl`
  - 已核：`00-http.conf` + `10-https.conf` 均含四 `server_name`（各 4 个），重叠一致
- [ ] HTTPS 证书就位：`deploy/gateway/certs/`（Let's Encrypt），443 生效
- [ ] 端口放行确认：80/443（网关）、8080/8081 等

## E. 数据 / 演示数据

- [x] `PACC_SEED=false`（生产不注入演示数据，当前已验证）
- [x] MySQL 为生产数据库，H2 仅限 `local` profile
- [x] 数据库迁移/初始化脚本就位（全部业务表，Flyway 版本化）
  - 已引入 Flyway：`flyway-core` + `flyway-mysql` 依赖；`db/migration/V1__init.sql` 覆盖 18 张表 DDL（唯一约束、索引齐全）
  - 生产 `ddl-auto: validate`（fail-closed 防漂移）+ `flyway.enabled=true` + `baseline-on-migrate=true`；local 用 H2 保持 `update` 并关闭 flyway
  - 已验证：V1 在真实 MySQL 8 完整执行成功（含 3 处索引列名 `tournamentId`→`tournament_id` 修复）；存量库已 baseline 到版本 1（现库不重建、数据保留），后续建表/改表走 `V2__*.sql` 增量迁移
- [x] 业务实体：18 个 `@Entity` 覆盖账号/反作弊/赛事/申诉等

## F. 测试与构建门禁

- [x] 后端 `mvn test`：37 用例全过
- [x] 前端 `npm run build`：tsc + vite 通过
- [x] 评分（12 例）+ 红屏（10 例）确定性测试通过
- [x] CI/CD 安全门禁：`ci.yml` 已含 gitleaks（PR 密钥扫描）+ Trivy（镜像漏洞 SARIF）；CI `permissions: contents: read` 最小权限 + concurrency 取消陈旧任务
  - CD `environment: production`（需仓库启用"要求审批"环境规则）、仅 `v*` tag 触发、Helm 官方二进制 + sha256 校验、敏感值仅从 `secrets.*` 注入
- [ ] 生产仓库已启用 production 环境的"要求审批"保护规则（针对 CD 部署下发前人工批准）
- [ ] 仓库已配置 CD 所需 Secrets：`KUBE_CONFIG` / `ADMIN_API_KEY` / `JWT_SECRET` / `MYSQL_ROOT_PASSWORD`

## G. 发布收尾

- [x] README 已同步（安全章节更新：Cookie / 飞书 / SMTP / fail-closed）
- [ ] 发版 tag `v*` 触发 CD
- [ ] 域名 DNS 指向网关，子域证书校验通过
- [x] 上线前冒烟（本机全栈）：全部容器 healthy（mysql/backend/frontend/gateway/gateway-go/ai）
  - 已验：`/actuator/health`=200 UP；网关四子域路由可达；管理/玩家端未登录访问受保护接口均返回 401（fail-closed）；伪造 `pacc_admin` / `pacc_player` token 均被拒（401）；玩家门户、管理登录页前端 200
- [ ] 上线后冒烟：飞书员工登录一次、密码找回收邮件、登录审计日志正确

---

## 发布前必处理项（未完成）

1. **真实 SMTP**：`.env` 置 `PACC_MAIL_STUB_ENABLED=false` 并填真实 SMTP 凭证（否则密码找回功能不发送）。
2. **WSS 密钥一致性**：后端 `.env` 已填 `PACC_SECURITY_WSS_SIGN_SECRET`（代码侧就绪）；部署时需将玩家客户端 `PACC_CLIENT_WSS_SECRET` 配为同一值（部署约定）。
3. **飞书后台**：确认回调 URL、通讯录权限、白名单已配置。
4. **网关域名/证书/端口**：按集群部署环境放行并配置 HTTPS。