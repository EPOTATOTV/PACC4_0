#!/usr/bin/env bash
# ============================================================
# PACC v4.0 生产一键部署脚本（Debian/Ubuntu，root 或 sudo 执行）
# 用法：
#   chmod +x deploy/server/deploy-server.sh
#   ./deploy/server/deploy-server.sh            # 启动并等健康
#   ./deploy/server/deploy-server.sh --down      # 停止
#   ./deploy/server/deploy-server.sh --renew     # 仅重建网关（HTTPS 证书后）
# 依赖：项目根目录运行；需已装 Docker（未装会自动安装）。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/../.."          # 切到项目根（docker-compose.yml 所在）

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; NC='\033[0m'
ok()   { printf "${GREEN}✓ %s${NC}\n" "$*"; }
warn() { printf "${YELLOW}! %s${NC}\n" "$*"; }
die()  { printf "${RED}✗ %s${NC}\n" "$*"; exit 1; }

# ---------- 1. 依赖：Docker ----------
if ! command -v docker >/dev/null 2>&1; then
  echo "▶ 未检测到 Docker，开始安装..."
  curl -fsSL https://get.docker.com | sh
  ok "Docker 已安装"
fi
docker --version >/dev/null 2>&1 || die "Docker 不可用，请检查"
docker compose version >/dev/null 2>&1 || die "docker compose 不可用，请安装 docker-compose-plugin"

# ---------- 2. 配置预检：.env ----------
[ -f .env ] || cp .env.production.example .env
# 若 .env 仍是模板（含占位符），说明没填完，拒绝继续（fail-closed）
grep -q '<改成' .env && die "⚠ .env 仍含占位符 <改成...>，请先填真实值再重跑"

# ---------- 3. 动作分支 ----------
case "${1:-up}" in
  down)
    docker compose down
    echo "▶ 已停止（数据保留）。如需彻底删除数据：docker compose down -v"; exit 0 ;;
  renew)
    warn "正在重建网关以应用新 HTTPS 证书..."
    docker compose build gateway
    docker compose up -d gateway
    exit 0 ;;
  up|*) : ;;   # 默认 up
esac

# ---------- 4. 构建并启动 ----------
echo "▶ 构建并启动全部服务（首次约 10~30 分钟）..."
docker compose up -d --build

# ---------- 5. 健康检查：最多等 120 秒 ----------
echo "▶ 等待所有服务 healthy..."
health_of() { # $1=service -> healthy/unhealthy/starting/空
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "$(docker compose ps -q "$1")" 2>/dev/null || echo ""
}
n=0
until [ $n -ge 120 ]; do
  all_ok=1
  for s in mysql ptv-backend ptv-frontend gateway-go ai; do   # gateway 无 healthcheck，用运行状态单查
    st=$(health_of "$s")
    [ "$st" = "healthy" ] || { all_ok=0; warn "  $s=$st"; }
  done
  gw=$(docker inspect --format '{{.State.Status}}' "$(docker compose ps -q gateway)" 2>/dev/null || echo down)
  [ "$gw" = "running" ] || { all_ok=0; warn "  gateway=$gw(应为 running)"; }
  [ "$all_ok" = "1" ] && { ok "全部健康"; break; }
  n=$((n+5)); sleep 5
done

echo "▶ 当前状态："
docker compose ps

# ---------- 6. 后端健康端点 + Flyway 版本 ----------
echo "▶ 后端 /actuator/health:"
curl -sf http://127.0.0.1:${PACC_BACKEND_PORT:-8080}/actuator/health && echo || warn "health 端点不可达（容器间直连算内网，公网访问走网关）"

echo "▶ Flyway 迁移版本："
docker compose exec ptv-backend sh -c \
  "curl -sf http://127.0.0.1:8080/actuator/flyway 2>/dev/null | grep -o '\"version\":\"[0-9]*\"' | head -1 || echo '(flyway 端点未暴露，属正常)'"

# ---------- 7. 鉴权 fail-closed 冒烟 ----------
echo "▶ 管理端未登录 /api/admin/me（应 401，fail-closed）:"
curl -s -o /dev/null -w '  http=%{http_code}\n' http://127.0.0.1:${PACC_BACKEND_PORT:-8080}/api/admin/me

ok "部署完成。公网访问请走网关：http(s)://admin.potatotv.asia（详见 deploy/server/README.md）"