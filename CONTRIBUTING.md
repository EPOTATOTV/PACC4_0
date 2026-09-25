# 参与贡献

环境搭建、各模块怎么构建、调试技巧这些东西在 [DEVELOPER.md](DEVELOPER.md) 里，这份文件只讲流程和规矩。

## 报问题

- 缺陷 → Bug 模板
- 功能想法 → 功能模板，或者先去 Discussions 的 Ideas 聊
- 装不上、连不上、文档看不懂 → Discussions 的 Q&A，不开 issue
- 能造成实际影响的安全缺陷 → 走 [私密漏洞上报](SECURITY.md)，不要开公开 issue

## 提代码

1. Fork，或者在这个仓库开分支，别直接往 `master` 推
2. 分支名带上前缀，例如 `fix/ci-trivy-tag`、`feat/device-fingerprint`
3. 提交信息按 Conventional Commits 写，scope 用目录名：`fix(ptv-client): 修正发行 JAR 混淆校验`、`docs(readme): 补部署入口`
4. 开 PR，把 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md) 里的检查项老实勾完

提交前本地至少跑一遍你这块相关的构建和测试：

```bash
cd ptv-backend && mvn -B package
cd ptv-client  && mvn -B package
cd ptv-frontend && npm run lint && npm run test:ci && npm run build
```

前端 ESLint 不允许有告警和错误，`any` 不要用；React hooks 的依赖数组不要靠注释屏蔽，该包 `useCallback` 就包。

## 版本号

以 `ptv-backend/pom.xml` 里的 `<version>` 为唯一基准，仓库里其余几十处版本字段靠脚本同步：

```bash
bash scripts/bump-version.sh 5.4.0          # 改
bash scripts/bump-version.sh 5.4.0 --check  # 校验，CI 里跑的就是这个
```

手改单个 pom 或 package.json 一定会被 `version-consistency` job 拦下来。

## 数据库

表结构变更一律走 Flyway，新脚本放 `ptv-backend/src/main/resources/db/migration/`，版本号在当前最大值之上递增，不要改已经合入的脚本。写完在 MySQL 8 上真跑一次，别只在 H2 上验。

## 三条架构约束

这三条不是建议，是产品定义的一部分。PR 里会逐条检查：

- 本系统不封禁玩家，只做本地检测、留痕、红屏警告、远程查端。不要加任何封禁能力，也不要为第三方封禁系统预留接口。
- 检测数据只来自玩家设备本机。不接入游戏服务器 API，不读游戏服务器数据库。
- 管理后台必须能独立部署，不能和游戏服务器产生部署耦合。

## 文档

README 和部署教程保持简单：能照着做完部署就行，不要写商业配置、密钥来源、内部环境信息。开发文档（DEVELOPER.md、docs/）可以写细，面向会一点 Java / 前端的人。

写文档的时候少来点套话，"强大""无缝""高效"这类词能删就删，句子别清一色排比。

## 别提交这些

密钥与证书（`.pem` `.key` `.p12` `.pfx` `.jks`），service account 与凭据 json，调试脚本产物（`_err*.txt` `_probe*.txt` `_start*.txt` `_tail*.txt`），内部设计文档与发布清单。`.gitignore` 里已经挡了一批，但它是兜底，不是借口。

## 行为准则

见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。