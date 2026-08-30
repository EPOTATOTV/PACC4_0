# PACC 生产部署（Docker Compose · 单服务器）

对应代码：
- 编排入口：[docker-compose.yml](../../docker-compose.yml)
- 环境模板：[.env.production.example](./.env.production.example)
- 一键脚本：[deploy-server.sh](./deploy-server.sh)
- 网关证书：[deploy/gateway/certs](../gateway/certs/README.md)

## 一、服务器要求

- Debian/Ubuntu（教程以 Ubuntu 22.04 为例）
- 建议配置：2 核 / 4GB 内存起 / 40GB 硬盘
- 已装 Docker（未装时脚本会自动安装）

## 二、上线步骤（三步）

```bash
# 1) 把项目传到服务器（任选其一）
git clone https://github.com/你的仓库/pacc.git && cd pacc
# 或 scp：scp -r d:\pacc root@IP:/root/pacc && cd /root/pacc

# 2) 填生产配置
cp deploy/server/.env.production.example .env
nano .env        # 逐项填真实值，见第四节必填清单

# 3) 一键部署
chmod +x deploy/server/deploy-server.sh
./deploy/server/deploy-server.sh
```

脚本会自动：装 Docker → 预检 `.env` → 构建启动全部服务 → 等所有容器 healthy → 检查健康端点 / Flyway 版本 → 做一次鉴权 fail-closed 冒烟（`/api/admin/me` 应 401）。

## 三、对外访问（四子域统一走网关 gateway）

正式生产只开放 **80/443**，全部流量经 `gateway` 按域名分流；后端 8080、前端 8081 等**不要**直接暴露公网。

| 子域 | 用途 |
|---|---|
| `admin.potatotv.asia` | 管理后台前端 |
| `api.potatotv.asia` | 后端 REST / WSS |
| `pacc.potatotv.asia` | 玩家门户 + 长连接 |
| `dl.potatotv.asia` | 客户端下载站 |

1. 在域名商把四个子域 A 记录解析到服务器公网 IP。
2. 放行端口：`sudo ufw allow 80,443/tcp`（云厂商安全组同开 80/443）。
3. 配置 HTTPS（推荐）：见 `deploy/gateway/certs/README.md`，用 certbot 签发四个域名，把 `potatotv.asia.crt` / `potatotv.asia.key` 放入 `deploy/gateway/certs/`，然后：
   ```bash
   ./deploy/server/deploy-server.sh --renew
   ```

## 四、`.env` 必填清单（fail-closed：缺失即拒启）

| 变量 | 说明 | 生成方式 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | 数据库密码 | 16+ 位强密码 |
| `PACC_ADMIN_API_KEY` | 前台登录 Key | `openssl rand -hex 48` |
| `PACC_SECURITY_SUPER_ADMIN_KEY` | 超级管理员 Key | 同上，不同值 |
| `PACC_SECURITY_JWT_SECRET` | JWT 签名（≥32B） | `openssl rand -hex 48` |
| `PACC_SECURITY_WSS_SIGN_SECRET` | WSS 签名（≥16B） | 同上，**须与玩家端 `PACC_CLIENT_WSS_SECRET` 一致** |
| `SMTP_HOST/PORT/USERNAME/PASSWORD/FROM` | 密码找回邮件 | 真实 SMTP（`PACC_MAIL_STUB_ENABLED=false`） |
| `PACC_FEISHU_ENABLED` + `APP_ID/SECRET` + 白名单 | 管理员飞书登录 | 可选，开启后须加白名单 |

⚠️ 密钥见 [RELEASE_CHECKLIST.md 发布前必处理项](../../RELEASE_CHECKLIST.md)。`.env` 含私密信息，切勿提交版本库（已在 `.gitignore`）。

## 五、常用运维命令

```bash
docker compose ps                    # 状态
docker compose logs -f ptv-backend   # 后端日志
docker compose restart ptv-backend   # 重启
docker compose down                  # 停止（数据保留）
./deploy/server/deploy-server.sh --down      # 停止
./deploy/server/deploy-server.sh --renew     # 应用新 HTTPS 证书
# 数据库备份
docker exec pacc-mysql sh -c 'mysqldump -upacc -p"$MYSQL_PASSWORD" pacc' > backup.sql
```

## 六、常见问题

- **启动失败**：多为 `.env` 占位符未替换或密钥缺失。看 `docker compose logs ptv-backend`。
- **生效新 `.env`**：改完必须 `docker compose up -d --build` 重建。
- **MySQL 数据持久化**：存于 volume `pacc-mysql-data`，`down` 不丢，`down -v` 才清空。