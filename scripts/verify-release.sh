#!/usr/bin/env bash
# 校验发布件完整性：对比 dist/<version>/SHA256SUMS.txt 与文件实际哈希。
#
# 用法：
#   ./scripts/verify-release.sh 5.4.0
#
# 退出码：0 全部匹配；1 有不匹配或缺少校验和。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "用法: $0 <版本号>" >&2
  exit 2
fi

BUILD_DIR="dist/${VERSION}"
SUMS="${BUILD_DIR}/SHA256SUMS.txt"

if [ ! -f "$SUMS" ]; then
  echo "找不到校验和文件：${SUMS}" >&2
  exit 1
fi

echo "== 校验 ${BUILD_DIR} =="

fail=0

# 校验和只覆盖当次构建产出的发布件；发布目录里可能混有手工放入的素材，
# 所以以 SHA256SUMS.txt 里列出的文件为准，而不是扫整个目录。
while read -r expected name; do
  [ -z "${name:-}" ] && continue
  file="${BUILD_DIR}/${name#\*}"
  if [ ! -f "$file" ]; then
    printf '  [缺失] %s\n' "$name"
    fail=1
    continue
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  else
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  fi
  if [ "$actual" = "$expected" ]; then
    printf '  [通过] %s\n' "$name"
  else
    printf '  [不匹配] %s\n    期望 %s\n    实际 %s\n' "$name" "$expected" "$actual"
    fail=1
  fi
done <"$SUMS"

if [ "$fail" -ne 0 ]; then
  echo "校验未通过。" >&2
  exit 1
fi

echo "全部通过。"
echo "下载站发布前请同步：deploy/dl-web/files/version.json 里的 sha256 字段。"