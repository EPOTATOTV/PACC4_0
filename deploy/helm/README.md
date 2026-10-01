# PACC Helm Chart

把后端 / 前端 / MySQL / nginx 网关 / Go 网关 / AI 推理打包成可复用的 Helm Chart，供 CI（`.github/workflows/cd.yml` 的 `deploy` job）与人工部署使用。

## 结构

```
deploy/helm/
├── Chart.yaml                  # 元数据（v5.4.0）
├── values.yaml                 # 全局参数（镜像、副本、资源、域名、密钥、组件开关）
└── templates/
    ├── _helpers.tpl            # 名称 / 镜像 / MySQL 连接串辅助函数
    ├── configmap.yaml          # 非敏感配置（TZ / 阈值 / SMTP / 飞书）
    ├── secret.yaml             # 管理员 / JWT / MySQL / SMTP / 飞书密钥
    ├── backend.yaml            # 后端 Deployment + Service（+ ptv-backend 兼容别名）
    ├── backend-hpa.yaml        # 后端 HPA（autoscaling.backend.enabled）
    ├── frontend.yaml           # 前端 Deployment + Service（+ ptv-frontend 兼容别名）
    ├── mysql.yaml              # MySQL StatefulSet（mysql.enabled）
    ├── mysql-service.yaml      # MySQL Service
    ├── mysql-pvc.yaml          # MySQL PVC
    ├── gateway.yaml            # nginx 域名网关 Deployment + Service（gateway.enabled）
    ├── gateway-go.yaml         # Go 边缘网关（gatewayGo.enabled）
    ├── ai.yaml                 # AI 推理服务 + PVC + Service（ai.enabled）
    ├── networkpolicy.yaml      # 默认拒绝 + 按组件放行（networkPolicy.enabled）
    └── ingress.yaml            # 四域名统一入口（TLS）
```

## 使用

```bash
# 校验语法
helm lint deploy/helm

# 渲染查看（密钥为必填）
helm template pacc deploy/helm \
  --set-string secrets.adminApiKey=... \
  --set-string secrets.superAdminKey=... \
  --set-string secrets.jwtSecret=... \
  --set-string secrets.wssSignSecret=... \
  --set-string secrets.mysqlRootPassword=...

# 安装
helm install pacc deploy/helm \
  --set-string secrets.adminApiKey=... \
  --set-string secrets.superAdminKey=... \
  --set-string secrets.jwtSecret=... \
  --set-string secrets.wssSignSecret=... \
  --set-string secrets.mysqlRootPassword=...
```

## 密钥处理（fail-closed）

`values.yaml` 里所有密钥默认都是空串，不留任何演示默认值：

- `secrets.adminApiKey`、`secrets.superAdminKey`、`secrets.jwtSecret`、`secrets.wssSignSecret` 必填，缺失时渲染阶段直接报错终止；
- 内置 MySQL 开启时 `secrets.mysqlRootPassword` 必填；
- `secrets.smtpPassword`、`secrets.feishuAppSecret` 为可选项，按需注入；
- 内置 MySQL 的应用账号密码优先取 `secrets.mysqlPassword`，为空时回退 `mysqlRootPassword`——`mysql:8` 镜像只有在 `MYSQL_PASSWORD` 非空时才会创建 `MYSQL_USER`，留空会导致应用连不上库。

这样定死是因为后端 `StartupSecretGuard` 在生产（非 local）下本身就会校验这四个密钥，以及 `SMTP_HOST`（除非 `PACC_MAIL_STUB_ENABLED=true`）。渲染期拦住比让 Pod 起来后 CrashLoopBackOff 更容易定位。CI 的 `deploy` job 已按同一份契约注入：四个安全密钥取自 GitHub Secrets 的 `ADMIN_API_KEY` / `SUPER_ADMIN_KEY` / `JWT_SECRET` / `WSS_SIGN_SECRET`，邮件配置取自仓库变量 `PACC_MAIL_STUB_ENABLED` / `SMTP_HOST` / `SMTP_FROM`。人工部署时这些值需要自行提供。

## 组件开关

`gateway.enabled`、`gatewayGo.enabled`、`ai.enabled`、`mysql.enabled`、`networkPolicy.enabled` 均可在 values 中切换，默认值见 `values.yaml`。

## 与 Docker Compose 的关系

- `docker-compose.yml`：单机开发 / 小型部署
- `deploy/helm`：Kubernetes 生产编排（多副本、滚动更新、HPA）

前端与 nginx 网关镜像内的配置写死上游主机名 `ptv-backend` / `ptv-frontend`，故 Chart 额外提供了这两个同名 Service 别名，保证容器内反向代理可解析。

> Ingress 依赖集群安装 ingress-nginx；TLS 由 `ingress.tlsSecret`（或 Cert-Manager）提供。网关自身的 HTTPS 证书需放入 `gateway.tlsSecret`，键为 `potatotv.asia.crt` / `potatotv.asia.key`。