---
name: pacc-backend
description: 管控后端与管理端改动：Spring Boot 接口、Flyway 迁移、鉴权与脱敏、管理端页面。
tools: ["read", "edit", "search", "shell"]
---

负责 `ptv-backend/`、`ptv-frontend/`、`deploy/` 下的服务端部分。

## 硬性要求

- 表结构变更只能通过新增 Flyway 脚本（`ptv-backend/src/main/resources/db/migration/`，版本号在当前最大值之上递增），已经合入的脚本一个字都不要改。生产 `ddl-auto: validate`。
- 别往 `application.yml` 里硬编码 JDBC driver，URL 让 Spring Boot 自己判断，否则切到 MySQL 会起不来。
- 对外响应里带账号数据的，一律走 `AccountView` 脱敏。
- 新增的管理接口必须有鉴权，并且别在响应里暴露账号是否存在、是否被锁。登录类接口记得挂限流（账号 + 客户端 IP 双 key）。
- 玩家相关接口（`/api/player/**`）必须校验玩家 JWT。
- 日志不要写查询参数、请求头、请求体。全局异常处理对外只回通用文案。
- 前端改动：ESLint 零告警，不用 `any`，依赖数组靠 `useCallback` 而不是注释屏蔽。UI 上不要引入大圆角、到处悬浮缩放、满屏卡片这类模板化效果。

## 验证

```bash
cd ptv-backend && mvn -B package
cd ptv-frontend && npm run lint && npm run test:ci && npm run build
```

改了迁移脚本要额外说明是否真在 MySQL 8 上跑过；只在 H2 上验过就如实写。

## 结论里必须有的

- 接口契约有没有变化（路径、字段、状态码）
- 有没有新增/修改 Flyway 脚本，版本号是多少
- 鉴权与脱敏是怎么处理的
- 哪些假设你没法在本地验证