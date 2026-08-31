# PACC 生产部署（Docker Compose · 单服务器）

对应代码：
- 编排入口：[docker-compose.yml](../../docker-compose.yml)
- 环境配置：根目录 `.env`（已存在于项目，参考 `.env.example`）
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

# 2) 配置根目录 .env（脚本直接读取它）
#    本机开发用的 .env 已存在且密钥基本就绪；上线前把 4 个占位/开发项改成真实值即可：
#      - MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD  （现为 change-me，去改成强密码）
#      - PACC_MAIL_STUB_ENABLED 从 true 改 false，并填 SMTP_HOST/USERNAME/PASSWORD/FROM
nano .env        # 逐项填真实值，见第四节必填清单

# 3) 一键部署
chmod +x deploy/server/deploy-server.sh
./deploy/server/deploy-server.sh
```

脚本会自动：装 Docker → **预检根目录 `.env`**（密钥齐全且非占位符，否则 fail-closed 拒启）→ 构建启动全部服务 → 等所有容器 healthy → 检查健康端点 / Flyway 版本 → 做一次鉴权 fail-closed 冒烟（`/api/admin/me` 应 401）。

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

## 五、客户端发行（Windows 安装向导 + 自动更新）

发行物在 `dl.potatotv.asia` 分发，装机后 `PaccManager` 自行管理探针更新。

| 产物 | 路径 | 用途 |
|---|---|---|
| 安装向导 | `tools/installer/Output/PACCClientSetup-4.0.0.exe` | 一键安装，含 `PaccManager.exe` + 探针 jar + 配置 |
| 下载包 | `deploy/dl-web/files/pacc-client-windows-x64-v4.0.0.zip` | 免安装压缩包（`dl` 域直下） |
| 版本清单 | `deploy/dl-web/files/version.json` | 自动更新对照（client + probe 各自 sha256） |
| 探针发布件 | `deploy/dl-web/files/ptv-agent-4.0.0.jar` | 供 PaccManager 相对下载替换 |

重新打包（需 .NET 8 Desktop SDK + Maven）：
```powershell
powershell -ExecutionPolicy Bypass -File tools\windows-gui\build-client.ps1
```

重新编译安装向导（需 Inno Setup，装好后）：
```powershell
& "C:\Program Files\Inno Setup 7\ISCC.exe" "tools\installer\pacc-client-installer.iss"
```

### 安装向导手动验证（必须在真实桌面，勿在隔离终端）

`PaccManager.exe` 声明 `requireAdministrator`（写 Program Files 需提权），故**静默/自动安装验证无法在沙箱终端完成**（UAC 授权弹框会挂起）。首次发布前请在有桌面会话的机器上双击运行：

```
tools\installer\Output\PACCClientSetup-4.0.0.exe
```

- 点「是」通过 UAC 提权 → 进入 Inno 向导
- 默认装到 `C:\Program Files\PACC 客户端\`，可选桌面快捷方式
- 装后目录应含：`PaccManager.exe`、`bin\ptv-agent-4.0.0.jar`、`pacc-client.properties`、`deploy\installer.ps1`
- 开始菜单出现「PACC 客户端」；「设置 → 应用」可卸载
- 完成可选「现在启动」，首次启动应拉取 `dl.potatotv.asia/files/version.json` 检查更新（离线则静默）

## 六、常用运维命令

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

## 七、常见问题

- **启动失败**：多为 `.env` 占位符未替换或密钥缺失。看 `docker compose logs ptv-backend`。
- **生效新 `.env`**：改完必须 `docker compose up -d --build` 重建。
- **MySQL 数据持久化**：存于 volume `pacc-mysql-data`，`down` 不丢，`down -v` 才清空。