# PACC Helm Chart

按环境清单「Helm K8s 应用包管理」交付。将后端 / 前端 / MySQL / Ingress 打包为可复用的 Helm Chart。

## 结构

```
deploy/helm/
├── Chart.yaml            # 元数据（v4.0.0）
├── values.yaml           # 全局参数（镜像、副本、资源、域名、密钥）
└── templates/
    ├── _helpers.tpl      # 名称/镜像/连接串辅助函数
    ├── secret.yaml       # API Key / JWT / MySQL 凭据
    ├── backend.yaml      # Spring Boot Deployment + Service + 探针
    ├── frontend.yaml     # nginx 静态站 Deployment + Service
    ├── mysql.yaml        # MySQL StatefulSet + PVC（可选）
    └── ingress.yaml      # 四域名统一入口（TLS）
```

## 使用

```bash
# 校验语法
helm lint deploy/helm

# 渲染查看
helm template pacc deploy/helm

# 安装（先准备 TLS 证书 secret：kubectl create secret tls potatotv-tls --cert=... --key=...）
helm install pacc deploy/helm \
  --set secrets.adminApiKey=... \
  --set secrets.jwtSecret=... \
  --set secrets.mysqlPassword=...
```

## 与 Docker Compose 的关系

- `docker-compose.yml`：单机开发 / 小型部署
- `deploy/helm`：Kubernetes 生产编排（多副本、滚动更新、自动伸缩扩展点）

> Ingress 依赖集群安装 ingress-nginx；TLS 由 `potatotv-tls` Secret（或 Cert-Manager）提供。