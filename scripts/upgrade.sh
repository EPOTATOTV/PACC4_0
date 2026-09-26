#!/usr/bin/env bash
# PACC 版本升级（Docker Compose 部署形态）
#
# 用法：
#   ./scripts/upgrade.sh 5.4.0 --dry-run     # 只打印将要执行的动作
#   ./scripts/upgrade.sh 5.4.0               # 备份 → 换版本 → 重建 → 健康检查
#
# 为什么要有这个脚本：升级的每一步单看都简单，但顺序错了会丢数据——
# 尤其是「先停写、再备份」这两步，手快的人容易反过来。这里把顺序固化了。
# 详细的配置变更清单见 docs/UPGRADE.md。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-}"
DRY_RUN="no"

for arg in "$@"; do
  [ "$arg" = "--dry-run" ] && DRY_RUN="yes"
done

if [ -z "$TARGET" ] || [ "$TARGET" = "--dry-run" ]; then
  echo "用法: $0 <目标版本如 5.4.0> [--dry-run]" >&2
  exit 2
fi

if ! printf '%s' "$TARGET" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号必须是 x.y.z 形式，收到：$TARGET" >&2
  exit 2
fi

if [ ! -f docker-compose.yml ]; then
  echo "当前目录没有 docker-compose.yml；裸机部署请按 docs/UPGRADE.md 第四节手工操作。" >&2
  exit 2
fi

run() {
  if [ "$DRY_RUN" = "yes" ]; then
    printf '  [dry-run] %s\n' "$*"
  else
    printf '  $ %s\n' "$*"
    "$@"
  fi
}

# .env 决定库名/口令，compose 自己也会读；这里读进来是为了拼 mysqldump 命令
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

DB_NAME="${MYSQL_DATABASE:-pacc}"
DB_ROOT_PW="${MYSQL_ROOT_PASSWORD:-pacc-root}"
BACKUP="pacc_backup_$(date +%Y%m%d_%H%M%S).sql"
CURRENT="$(perl -0ne 'print $1 if m{<artifactId>ptv-backend</artifactId>\s*<version>([^<]+)}' ptv-backend/pom.xml 2>/dev/null || echo unknown)"

echo "================================================"
echo " PACC 升级：${CURRENT} → ${TARGET}${DRY_RUN:+（dry-run）}"
echo "================================================"

echo "[1/5] 停止后端写入"
run docker compose stop ptv-backend

echo "[2/5] 备份数据库 → ${BACKUP}"
if [ "$DRY_RUN" = "yes" ]; then
  printf '  [dry-run] docker compose exec -T mysql mysqldump ... > %s\n' "$BACKUP"
else
  docker compose exec -T mysql \
    mysqldump -u root -p"${DB_ROOT_PW}" --single-transaction --routines \
    "${DB_NAME}" >"${BACKUP}"
  if [ ! -s "${BACKUP}" ]; then
    echo "备份文件为空，中止升级。" >&2
    exit 1
  fi
  echo "  备份大小：$(wc -c <"${BACKUP}") 字节"
fi

echo "[3/5] 统一版本号到 ${TARGET}"
run ./scripts/bump-version.sh "$TARGET"

echo "[4/5] 重建并启动（Flyway 会自动执行 V26–V34 迁移）"
run docker compose up -d --build

echo "[5/5] 健康检查"
if [ "$DRY_RUN" = "yes" ]; then
  printf '  [dry-run] curl -fsS http://localhost:8080/actuator/health\n'
else
  ok="no"
  for i in $(seq 1 30); do
    if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
      ok="yes"
      break
    fi
    sleep 2
  done
  if [ "$ok" = "yes" ]; then
    echo "  后端已就绪。"
    # 迁移是否真的成功，要比看启动日志更可靠
    docker compose exec -T mysql \
      mysql -u root -p"${DB_ROOT_PW}" "${DB_NAME}" \
      -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;" || true
  else
    echo "  后端 60 秒内未通过健康检查。查看日志：docker compose logs --tail=100 ptv-backend" >&2
    echo "  需要回滚：./scripts/rollback.sh ${BACKUP} ${CURRENT}" >&2
    exit 1
  fi
fi

echo
echo "升级完成。备份文件保留在 ${BACKUP}，确认稳定后再清理。"
echo "下一步：确认客户端已同步升级（下载站 min_version 已抬到 ${TARGET}）。"