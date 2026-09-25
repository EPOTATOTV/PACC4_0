#!/usr/bin/env bash
# PACC 版本回滚（Docker Compose 部署形态）
#
# 用法：
#   ./scripts/rollback.sh pacc_backup_20260928_120000.sql 5.3.0 --dry-run
#   ./scripts/rollback.sh pacc_backup_20260928_120000.sql 5.3.0
#
# 回滚 = 恢复数据库备份 + 把镜像标签换回旧版本。Flyway 迁移脚本不回退：
# V26–V34 多出来的表在旧版本里没有对应实体，ddl-auto=validate 不会因此报错，留着无害。
#
# 提醒：恢复备份会丢掉备份时间点之后的所有数据。回滚前先单独导出这段时间的数据。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BACKUP="${1:-}"
TARGET="${2:-}"
DRY_RUN="no"

for arg in "$@"; do
  [ "$arg" = "--dry-run" ] && DRY_RUN="yes"
done

if [ -z "$BACKUP" ] || [ -z "$TARGET" ]; then
  echo "用法: $0 <备份文件.sql> <目标版本如 5.3.0> [--dry-run]" >&2
  exit 2
fi

if [ ! -f "$BACKUP" ]; then
  echo "备份文件不存在：${BACKUP}" >&2
  exit 2
fi

if [ ! -f docker-compose.yml ]; then
  echo "当前目录没有 docker-compose.yml；裸机部署请按 docs/UPGRADE.md 第三节手工操作。" >&2
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

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

DB_NAME="${MYSQL_DATABASE:-pacc}"
DB_ROOT_PW="${MYSQL_ROOT_PASSWORD:-pacc-root}"

echo "================================================"
echo " PACC 回滚 → ${TARGET}（数据源：${BACKUP}）"
echo "================================================"

# 回滚是不可逆动作，dry-run 之外的路径要求显式确认，避免误敲回车丢掉线上数据
if [ "$DRY_RUN" != "yes" ]; then
  printf '这会覆盖当前数据库 %s 的全部内容。输入 yes 继续：' "${DB_NAME}"
  read -r answer
  if [ "$answer" != "yes" ]; then
    echo "已取消。"
    exit 1
  fi
fi

echo "[1/5] 停止后端与前端（避免回滚期间继续写库）"
run docker compose stop ptv-backend ptv-frontend ai

echo "[2/5] 恢复数据库"
if [ "$DRY_RUN" = "yes" ]; then
  printf '  [dry-run] docker compose exec -T mysql mysql -u root ... < %s\n' "$BACKUP"
else
  docker compose exec -T mysql \
    mysql -u root -p"${DB_ROOT_PW}" "${DB_NAME}" <"$BACKUP"
fi

echo "[3/5] 镜像标签改回 ${TARGET}"
if [ "$DRY_RUN" = "yes" ]; then
  printf '  [dry-run] ./scripts/bump-version.sh %s\n' "$TARGET"
else
  ./scripts/bump-version.sh "$TARGET"
fi

echo "[4/5] 启动"
run docker compose up -d

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
    echo "  回滚后服务已就绪。"
  else
    echo "  服务未通过健康检查：docker compose logs --tail=100 ptv-backend" >&2
    exit 1
  fi
fi

echo
echo "回滚完成。别忘了同步回退客户端与下载站："
echo "  - deploy/dl-web/files/version.json 的 client_version / min_version"
echo "  - .env 里的 PACC_IMAGE_TAG"