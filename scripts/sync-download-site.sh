#!/usr/bin/env bash
# 同步下载站制品：从 GitHub Release 取件 → 落到 deploy/dl-web/files/ → 校验 → 重算校验清单。
#
# 用法：
#   ./scripts/sync-download-site.sh 5.4.0            # 取件 + 校验 version.json + 重算 releases.json
#   ./scripts/sync-download-site.sh 5.4.0 --rebuild  # 额外重建网关镜像并重启（让下载站生效）
#
# 前置：gh 已登录（gh auth status），release.yml 已为 v<版本> 建好 Release。
#
# 为什么从 Release 取件而不是本地 dist/：本地打包产物不进库，服务器上也没有 .NET / JDK
# 工具链；Release 是对外唯一可信来源，且带 build provenance（gh attestation verify 可复核）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
REBUILD=false
case "${2:-}" in
  --rebuild) REBUILD=true ;;
  "") ;;
  *) echo "未知参数：$2（用法见脚本头部）" >&2; exit 2 ;;
esac

if [ -z "$VERSION" ]; then
  echo "用法: $0 <版本号> [--rebuild]" >&2
  exit 2
fi

TAG="v${VERSION#v}"
FILES_DIR="$ROOT/deploy/dl-web/files"

command -v gh >/dev/null 2>&1 || { echo "缺少 gh 命令（GitHub CLI），无法从 Release 取件" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== 从 Release $TAG 取件 =="
gh release download "$TAG" -D "$TMP"

mkdir -p "$FILES_DIR"
copied=0
# 只搬客户端制品：后端 jar、前端 dist、SBOM 不属于下载站
for f in "$TMP"/pacc-client-*.zip "$TMP"/pacc-client-*.tar.gz "$TMP"/pacc-client-*.apk \
         "$TMP"/pacc-client-*.hap "$TMP"/pacc-client-*.ipa \
         "$TMP"/ptv-agent-*.jar "$TMP"/*Setup-*.exe "$TMP"/version.json; do
  [ -f "$f" ] || continue
  cp -f "$f" "$FILES_DIR/"
  echo "  + $(basename "$f")"
  copied=$((copied + 1))
done
[ "$copied" -gt 0 ] || { echo "Release $TAG 里没有可同步的客户端制品" >&2; exit 1; }

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  fi
}

json_field() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
    "$FILES_DIR/version.json" | head -1
}

# version.json 是客户端自动更新的信任根：里面的 sha256 对不上，客户端要么误判"需更新"
# 而下载失败，要么把被替换的包当合法包装上，所以这里对不上就直接失败，不"同步完成"。
echo "== 校验 version.json 与落盘文件一致 =="
fail=0
for kind in client probe; do
  url="$(json_field "${kind}_url")"
  want="$(json_field "${kind}_sha256")"
  [ -n "$url" ] && [ -n "$want" ] || continue
  name="$(basename "$url")"
  if [ ! -f "$FILES_DIR/$name" ]; then
    printf '  [缺失] %s\n' "$name"; fail=1; continue
  fi
  got="$(hash_file "$FILES_DIR/$name")"
  if [ "$got" = "$want" ]; then
    printf '  [通过] %s\n' "$name"
  else
    printf '  [不匹配] %s\n    清单 %s\n    实际 %s\n' "$name" "$want" "$got"; fail=1
  fi
done
if [ "$fail" -ne 0 ]; then
  echo "校验未通过：version.json 与实际产物不一致，请确认 Release 制品是否被替换。" >&2
  exit 1
fi

echo "== 重算下载站产物清单 =="
mkdir -p "$ROOT/dist"
"$ROOT/deploy/dl-web/compute-sha256.sh" -o "$ROOT/dist/releases-${VERSION#v}.json"
echo "清单：dist/releases-${VERSION#v}.json（供管控台写入 t_dl_release 或追加 Flyway 迁移）"

if [ "$REBUILD" = true ]; then
  echo "== 重建网关镜像并重启（下载站静态资源烧在网关镜像里）=="
  docker compose build gateway
  docker compose up -d gateway
fi

echo "下载站制品已同步（$copied 个文件 → deploy/dl-web/files/）"