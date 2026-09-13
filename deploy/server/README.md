# PACC 生产部署（Docker Compose · 单服务器）

本教程用最简单的方式，把 PACC 整套服务部署到一台自己的服务器上。

- 编排入口：[docker-compose.yml](../../docker-compose.yml)
- 环境变量模板：根目录 `.env.example`
- 一键脚本：[deploy-server.sh](./deploy-server.sh)
- 网关 HTTPS 证书：见 [deploy/gateway/certs/README.md](../gateway/certs/README.md)

## 一、准备一台服务器

- 系统：Debian / Ubuntu（本教程以 Ubuntu 22.04 为例）
- 建议配置：2 核 / 4GB 内存 / 40GB 硬盘起步
- 需要 Docker；教程的脚本会自动安装，没装也没关系
- 准备一个域名，并把 `admin`、`api`、`pacc`、`dl` 四个子域解析到服务器 IP

## 二、三步完成部署

```bash
# 1) 把项目传到服务器
git clone 你的仓库地址 && cd 你的项目目录

# 2) 按模板配置环境变量（主要改密码和几个密钥）
cp .env.example .env
nano .env        # 逐项换成你自己的值，见第四节清单

# 3) 一键部署
chmod +x deploy/server/deploy-server.sh
./deploy/server/deploy-server.sh
```

脚本会自动：装 Docker → 检查 `.env` 密钥（缺了就拒绝启动，防止配置不全带病上线）→ 构建并启动全部服务 → 等所有容器健康 → 检查健康端点。

## 三、对外访问（四个子域统一走网关）

生产只开放 **80/443**，所有请求都经 `gateway` 按域名分流；后端 8080、前端 8081 等端口不要直接暴露到公网。

| 子域 | 用途 |
|---|---|
| `admin.你的域名` | 管理后台前端 |
| `api.你的域名` | 后端 REST / WSS |
| `pacc.你的域名` | 玩家门户 + 玩家长连接 |
| `dl.你的域名` | 客户端下载站 |

1. 在域名服务商把四个子域 A 记录解析到服务器公网 IP
2. 放行端口：`sudo ufw allow 80,443/tcp`（云厂商安全组同步放开 80/443）
3. 配置 HTTPS（推荐）：见 `deploy/gateway/certs/README.md`，用 certbot 签发四个子域证书后
   ```bash
   ./deploy/server/deploy-server.sh --renew
   ```

## 四、`.env` 必填项（缺了会拒绝启动）

| 变量 | 说明 | 生成方式 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | 数据库密码 | 用一长串强密码 |
| `PACC_ADMIN_API_KEY` | 前台登录 Key | `openssl rand -hex 48` |
| `PACC_SECURITY_SUPER_ADMIN_KEY` | 超级管理员 Key | 同上，用不同的值 |
| `PACC_SECURITY_JWT_SECRET` | JWT 签名 | `openssl rand -hex 48` |
| `PACC_SECURITY_WSS_SIGN_SECRET` | WSS 签名 | 同上，且须与玩家端 `PACC_CLIENT_WSS_SECRET` 一致 |
| `SMTP_HOST/PORT/USERNAME/PASSWORD/FROM` | 密码找回邮件 | 你的真实 SMTP（并把 `PACC_MAIL_STUB_ENABLED` 改 `false`） |
| `PACC_FEISHU_ENABLED` + `APP_ID/SECRET` + 白名单 | 管理员飞书登录 | 可选，开启后需配置回调白名单 |

> `.env` 里是私密信息，不要提交到版本库（已加入 `.gitignore`）。

## 五、常用运维命令

```bash
docker compose ps                         # 查看各容器状态
docker compose logs -f ptv-backend        # 看后端日志
docker compose restart ptv-backend        # 重启后端
docker compose down                       # 停止（数据保留）
./deploy/server/deploy-server.sh --renew  # 应用新 HTTPS 证书
```

## 六、常见问题

- **启动失败**：多半是 `.env` 里还是占位符或密钥缺失。看 `docker compose logs ptv-backend`。
- **改了 `.env` 不生效**：改完要重新 `docker compose up -d --build` 重建才生效。
- **数据会丢吗**：MySQL 数据存在 volume `pacc-mysql-data`，`down` 不丢；只有 `down -v` 才会清空。