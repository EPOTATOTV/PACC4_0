# PACC 网关证书（TLS/HTTPS）

网关支持两种运行模式：

## 1. 仅 HTTP（开箱即用）

无需任何证书，`docker compose up -d --build` 即可通过四个子域名访问。
适合内网 / 联调；公开部署建议启用 HTTPS。

## 2. 启用 HTTPS（推荐，生产）

### 方式 A：Let's Encrypt（推荐）

在服务器上执行（需域名已解析到本机 80/443）：

```bash
# 1. 用 certbot 一次性签发（-d 四个域名）
sudo apt install -y certbot
sudo certbot certonly --webroot -w /var/www/certbot \
  -d admin.potatotv.asia -d api.potatotv.asia \
  -d pacc.potatotv.asia -d dl.potatotv.asia

# 2. 合并为网关需要的单证书文件，放入本目录
sudo mkdir -p ./certs
sudo cp /etc/letsencrypt/live/potatotv.asia/fullchain.pem ./certs/potatotv.asia.crt
sudo cp /etc/letsencrypt/live/potatotv.asia/privkey.pem   ./certs/potatotv.asia.key

# 3. 重新构建启动（入口脚本检测到证书后自动启用 HTTPS）
docker compose up -d --build gateway
```

> 将 `potatotv.asia.crt` / `potatotv.asia.key` 放入本目录后，网关会自动
> 切换到 443 HTTPS 并将 80 重定向到 HTTPS。certbot 续期可配置 systemd timer。

### 方式 B：任意 CA 证书

将 `fullchain` 与私钥分别保存为 `potatotv.asia.crt` / `potatotv.asia.key` 放入本目录即可。

> 注意：证书文件含私钥，切勿提交到版本库（已加入 .gitignore）。
