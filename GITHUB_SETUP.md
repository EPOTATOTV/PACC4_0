# PACC GitHub 仓库手动配置清单

> 这些配置**只能在 GitHub 网页端操作**，Agent 无法代劳。按顺序在仓库 `Settings` 里逐项完成，完成后 CI/CD 才可实际运行。

## 0. 前置：先把代码推上 GitHub

本地 uncommitted 改动很多（Flyway、Cookie 迁移、飞书 SSO 等），先提交再推。建议推送前按发布清单核对一遍。

```
git add .
git commit -m "release: 安全基线 + Flyway 迁移 + 飞书 SSO 就绪"
git push -u origin main
```

## 1. Actions 权限（Settings → Actions → General → Workflow permissions）

- 选 **Read and write permissions**（CD 推送 ghcr 镜像需要写 packages，已在流程里声明 `packages: write`，如遇拒绝再确认此项）
- 允许 **allow GitHub Actions to create and approve pull requests**（dependabot 开 PR 需要）

## 2. GitHub Secrets（Settings → Secrets and variables → Actions）

cd.yml 用到以下 secrets，逐项新增（值来自你本地的 `.env` / 部署环境）：

| Secret 名 | 对应配置 | 说明 |
|---|---|---|
| `KUBE_CONFIG` | 集群 kubeconfig | base64 编码的 kubeconfig 内容 |
| `ADMIN_API_KEY` | `PACC_SECURITY_ADMIN_API_KEY` | 管理后台密钥 |
| `JWT_SECRET` | `PACC_SECURITY_JWT_SECRET` | JWT 签名密钥 |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | 生产数据库 |

> 可选：若还有 SMTP/飞书，建议一并加 `SMTP_*`、`PACC_FEISHU_*` 并在 cd.yml Helm 部署处引用（当前未接，后续需要再加）。

## 3. production 环境 + 审批保护（Settings → Environments → New environment）

1. 新建环境命名 **`production`**（cd.yml 的 `environment: production` 指向它）
2. 在 `Production environment rules` 里勾选 **Required reviewers → 添加 1-2 名批准人**（发布审批）
3. 可选：**Wait timer** 设等待期（如 0 / 10 分钟）

> 没有这个环境时，CD 的 deploy job 会因找不到环境而失败（或按仓库默认规则放行）。务必创建并配好 Required reviewers。

## 4. Code scanning（Settings → Code security and analysis）

- **Secret scanning** → Enable
- **Push protection** → Enable（拦截含密钥的 push）
- **Dependabot alerts** → Enable（依赖漏洞告警）
- **Code scanning** → 确认 `gitleaks` / `Trivy` 上传的 SARIF 能在这显示（依赖第 2 项与 workflow 的 `security-events: write`）

## 5. 分支保护（Settings → Branches → Add branch protection rule）

对 `main`：
- **Require status checks to pass before merging** → 勾选 CI 的 `java` / `frontend` / `go` / `rust` / `secret-scan` / `vuln-scan` jobs
- **Require pull request reviews before merging** → 1 人
- **Do not allow bypassing the above settings** 保留

## 6. 域名/DNS（在 DNS 服务商，非 GitHub）

把四个子域 CNAME/A 指向网关服务器公网 IP 或负载均衡：

```
admin.potatotv.asia
api.potatotv.asia
pacc.potatotv.asia
dl.potatotv.asia
```

## 7. 首轮清空旧 Code Scanning alert

如果本地开发期 gitleaks 曾把 `.env` 里的密钥上报过，清 Code Scanning 里的对应告警并**去飞书/密钥平台重置这些密钥**，再改 `.env` 用新值。

---

## 完成标志
- Secrets 4 项已建
- production 环境含 Required reviewers
- 分支保护勾上 CI jobs
- Code scanning 三项 enable

全部完成后再打 tag `v4.0.0` 触发 CD，Observe：CI 全绿 → 镜像推送 → image-scan 过 → 人工批准 → Helm 部署。