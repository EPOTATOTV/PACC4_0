# 安全策略

PACC 是反作弊客户端，被逆向、被绕过的价值很高；管理后台又直接持有玩家查端能力和证据数据。所以安全问题的处理方式单独说明。

## 上报漏洞

能造成实际影响的缺陷走 GitHub 私密漏洞上报，不要开公开 issue、不要发到 Discussions：

**https://github.com/EPOTATOTV/PACC4_0/security/advisories/new**

至少要给出这些信息：

- 影响的是哪一端（玩家端 / 管理后台 / 网关 / 下载站）
- 版本或 commit
- 复现步骤，或给出能说明问题的流量、截图、日志片段
- 你判断的影响面

密钥、token、玩家真实 PTEID 一律不要出现在上报内容里。如果问题本身就涉及某个密钥，只写变量名（例如 `PACC_SECURITY_JWT_SECRET`），值不要贴。

## 我们认为的"安全问题"

这一类请按漏洞上报：

- 绕过本地检测、绕过红屏锁定，或伪造红屏解锁
- 伪造、重放、篡改 WSS 信令（HMAC 签名、时间窗、nonce 相关的绕过）
- 越权访问管理接口、越权发起远程查端、越权提取证据
- 玩家 token 或 PTEID 的跨账号冒用
- 服务端密钥、SMTP、飞书 SSO 配置的泄露路径
- 依赖或镜像中的已知可利用漏洞，且在本项目部署方式下真的可利用

## 不算安全问题

- 没有复现步骤的扫描器告警（Dependabot / Trivy 的原始条目请走 issue 模板）
- 需要已拿到管理员权限、已能改本机文件或已能改系统时间的前置条件
- 检测能力本身的"效果不好"（漏检、误报）——这属于缺陷，走 Bug 模板
- 压缩包体积、性能、界面问题

## 部署方需要知道的事

这个仓库里的默认值只保证开发环境能跑起来。上线前必须自己处理的：

- `PACC_SECURITY_ADMIN_API_KEY`、`PACC_SECURITY_SUPER_ADMIN_KEY`、`PACC_SECURITY_JWT_SECRET`、`PACC_SECURITY_WSS_SIGN_SECRET`、SMTP 与飞书 SSO 配置，全部通过环境变量注入，非开发 profile 下缺任意一项应当启动失败
- `PACC_SEED`（演示数据）在生产必须关掉
- 邮件改密与飞书登录必须接真实 SMTP / OAuth，桩实现不得在生产启用
- 生产 profile 下 `ddl-auto` 为 `validate`，表结构变更走 Flyway 迁移
- Swagger / springdoc 默认关闭，只在 `PACC_SPRINGDOC_ENABLED=true` 时打开
- H2 控制台默认关闭，只在 `dev` profile 下打开
- 网关需要按四个子域分流（管理后台 / API 与 WSS / 玩家连接 / 下载站），并保留按 IP 的限流
- 若启用证书固定，`PACC_TLS_PIN_SHA256` 需同时满足系统信任链校验与叶证书 SPKI 指纹匹配

## 仓库自身的门禁

- gitleaks 扫提交历史里的密钥，Trivy 扫依赖与镜像，CodeQL 扫自己写的代码
- Secret scanning 与 push protection 已开，含密钥的 push 会被拦
- 生产发布走 `production` 环境，需要人工批准
- 密钥类告警如果确实误报，先确认历史提交里没有真实值，再在告警里标注忽略原因

## 响应

维护者看到上报后会先确认收到，再给处理计划。这是个规模很小的项目，不要期待 SLA；如果问题正在被实际利用，请在标题里写明，会优先看。