# 这个 PR 做了什么

<!-- 一句话说清目的。背景放在 issue 里，这里只写结论。 -->

关联 issue：Closes #

## 影响范围

- [ ] ptv-backend
- [ ] ptv-frontend
- [ ] ptv-client
- [ ] ptv-desktop / tools
- [ ] ptv-mobile
- [ ] platform（平台适配层）
- [ ] deploy（编排 / 网关 / AI / 监控）
- [ ] CI / 脚本
- [ ] 文档

## 三条架构约束

违反任意一条的改动不合入。逐条确认：

- [ ] 没有加入任何封禁玩家的能力（本系统只做本地检测、留痕、红屏警告、远程查端）
- [ ] 没有接入游戏服务器 API 或数据库，新采集的数据仍然只来自玩家设备本机
- [ ] 管理后台依旧可以独立部署，没有和游戏服务器产生部署耦合

## 验证

<!-- 写你实际跑过的命令，别写"应该没问题" -->

- [ ] `cd ptv-backend && mvn -B package`
- [ ] `cd ptv-client && mvn -B package`（产物加固校验见 ci.yml）
- [ ] `cd ptv-frontend && npm run lint && npm run test:ci && npm run build`
- [ ] 端侧或网关相关改动已在本机实跑
- [ ] 以上均不适用（纯文档改动）

需要人工确认的现象、截图、日志（脱敏后）贴在这里：

## 版本号与迁移

- [ ] 没动版本号（版本号以 `ptv-backend/pom.xml` 为唯一基准，由 `scripts/bump-version.sh` 统一改）
- [ ] 改了版本号，且已跑 `bash scripts/bump-version.sh <version> --check` 通过
- [ ] 不涉及数据库改动
- [ ] 包含 Flyway 迁移脚本，版本号在 V3x 之上递增，且已在 MySQL 8 上实跑过

## 安全自查

- [ ] 没有提交密钥、证书、凭据（`_err*.txt`、`*.pem`、`*.p12`、service account json 等）
- [ ] 日志与错误返回没有带出查询参数、请求头、请求体或玩家真实身份信息
- [ ] 新增的对外接口有鉴权，且没有在响应里暴露账号存在性或锁定状态