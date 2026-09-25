# 给 AI 编码代理的仓库说明

这份文件是给在仓库里干活的编码代理看的。人看的开发文档在 [DEVELOPER.md](DEVELOPER.md) 和 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 这个项目是什么

PACC，Minecraft 基岩版与 Java 版的全平台玩家端反作弊系统。玩家端做本地检测与留痕，管理后台（PTV）做远程查端与证据管理。它**不封禁玩家**。

## 三条不能破的约束

改任何东西之前先过一遍，这比代码风格重要得多：

1. 不加任何封禁能力。也不为第三方封禁系统预留接口。系统只做本地检测、留痕、红屏强制警告、远程查端。
2. 检测数据只来自玩家设备本机。不接入游戏服务器 API，不读游戏服务器数据库。
3. 管理后台必须能独立部署，不能和游戏服务器产生部署耦合。

如果需求本身要求突破其中一条，先停下来说明，不要"先实现再说"。

## 模块与构建

| 目录 | 内容 | 构建 / 测试 |
|---|---|---|
| `ptv-backend` | 管控后端（Java 21 + Spring Boot 3） | `mvn -B package`（含单测、JaCoCo、CycloneDX SBOM） |
| `ptv-client` | 玩家端用户态服务（Java 21） | `mvn -B package`，产物必须经过 ProGuard 加固 |
| `ptv-frontend` | 管理后台（TS + React + Vite） | `npm ci && npm run lint && npm run test:ci && npm run build` |
| `ptv-desktop` | 桌面壳（Tauri） | `npm run build` |
| `ptv-mobile` | 移动端（Capacitor + 原生探针） | 见 `ptv-mobile/README`，本机（Windows）编译不了 iOS |
| `platform/` | 平台适配层、内核模块、Java Agent、eBPF | 各子目录自带说明；Linux / 内核相关只能在 Linux runner 上编 |
| `deploy/` | 编排、Go 网关、Rust 管道、AI 服务、下载站 | `gateway-go`: `go vet ./... && go build`；`pipe-rust`: `cargo build --release` |

Windows 开发机上编不了的东西（内核驱动、eBPF、iOS、Android NDK）不要去硬试，改完说明清楚，让 CI 的对应 job 去验。

## 版本号

`ptv-backend/pom.xml` 的 `<version>` 是唯一基准，全仓库其余版本字段靠脚本同步：

```bash
bash scripts/bump-version.sh <x.y.z> --check
```

不要手改单个 `pom.xml` 或 `package.json` 的版本号，一定会在 CI 的 `version-consistency` job 上挂掉。

## 数据库

表结构变更走 Flyway，脚本放 `ptv-backend/src/main/resources/db/migration/`，版本号在当前最大值之上递增。已经合入的迁移脚本不要改。生产环境 `ddl-auto: validate`，写错会被启动校验拦下。

本地开发用 H2 内存库；不要往 `application.yml` 里硬编码 JDBC driver，切到 MySQL 会启动失败。

## 几条踩过的坑

- `@Builder.Default`：实体里带 NOT NULL 约束的字段，用 builder 时必须靠它保证非空。
- 对外返回账号数据统一走 `AccountView` 做脱敏，不要直接把实体丢出去，密码哈希绝不能出现在响应里。
- 登录、注册相关响应不能暴露"账号是否存在""是否被锁"，一律给通用错误文案。
- 玩家 token 的 subject 必须和 PTEID 校验绑定，否则会出现跨账号冒用。
- WSS 消息的 HMAC 签名覆盖面要包含 payload，签名只盖头等于没盖。
- `vite.config.js` 比 `vite.config.ts` 优先级高，仓库里只能存在一个，否则加固配置会被静默忽略。
- 日志与异常返回不要带查询参数、请求头、请求体；全局异常处理对外只给通用信息，堆栈只写服务端日志。
- 单元测试没跑过的改动不算改完。增量编译会掩盖方法名写错这类问题，重要改动跑一次 clean build。

## 不要提交的东西

密钥与证书（`.pem` `.key` `.p12` `.pfx` `.jks` `.p8`），service account / 凭据 json（`*.service-account*.json`、`*credentials*.json`），调试产物（`_err*.txt` `_probe*.txt` `_start*.txt` `_tail*.txt`），还有内部设计文档、发布清单、部署教程里的商业配置。发现密钥已经进了历史提交，就在 issue 里说明，并提醒去轮换该密钥。

## 代码风格

- Java：不用通配符 import；重写方法带 `@NonNull`；不留没用的 import、字段、依赖。
- 前端：ESLint 零告警零错误，不用 `any`，hooks 依赖不要靠注释屏蔽，该上 `useCallback` 就上。
- 注释只在"为什么"不显然的地方写，别把代码翻译一遍。

## 文档风格

README、部署教程从简，讲清楚怎么跑起来就行，不写商业信息。

开发文档可以写细，但要去掉 AI 腔：不要"强大/无缝/高效/现代化"这类空词，不要清一色排比和三段式列举，不要"综上所述""值得一提的是"。句子长短交错着写，可以口语化。emoji 少用，徽章少堆。

## 提交信息

Conventional Commits，scope 用目录名：

```
fix(ptv-client): 修正发行 JAR 混淆校验
feat(admin): 管理端新增 APM 监控页
docs(readme): 补部署入口
```

## 干完活之后

- 说清楚你跑了哪些命令、结果是什么。没跑就直说没跑。
- 改了 workflow、迁移脚本、`proto/` 这类跨端契约的东西，在总结里单独点出来，这些需要人再看一眼。
- 不要为了"顺手"去重构无关的代码。