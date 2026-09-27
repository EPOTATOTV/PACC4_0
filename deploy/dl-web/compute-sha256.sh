#!/usr/bin/env bash
# ============================================================
# 下载站发行物校验和：遍历 files/ 下的安装包，算出 SHA-256 与体积并输出 JSON。
#
# 用法：
#   ./deploy/dl-web/compute-sha256.sh                    # 打印到标准输出
#   ./deploy/dl-web/compute-sha256.sh -o releases.json   # 同时写文件
#
# 产物命名约定（决定 platform / artifact 字段）：
#   *windows* / *.exe / *.msi      -> WIN  client
#   *linux*   / *.tar.gz / *.deb   -> LNX  client
#   *android* / *.apk              -> APK  client
#   *ios* / *iphone* / *.ipa       -> IOS  client
#   *harmony* / *hmos* / *.hap     -> HMY  client
#   *.jar                          -> JVM  probe
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
FILES_DIR="$HERE/files"
OUT=""
VERSION=""

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--dir)     FILES_DIR="$2"; shift 2 ;;
    -o|--out)     OUT="$2"; shift 2 ;;
    -v|--version) VERSION="$2"; shift 2 ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（-h 查看用法）" >&2; exit 2 ;;
  esac
done

[ -d "$FILES_DIR" ] || { echo "找不到目录：$FILES_DIR" >&2; exit 1; }

# 版本号：命令行优先，其次 files/version.json 里的 client_version，最后 v0.0.0
if [ -z "$VERSION" ] && [ -f "$FILES_DIR/version.json" ]; then
  VERSION="$(sed -n 's/.*"client_version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$FILES_DIR/version.json" | head -1)"
fi
[ -n "$VERSION" ] || VERSION="v0.0.0"

hash_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  fi
}

# 文件名 -> 平台代码；识别不出返回空，跳过该文件
platform_of() {
  local n; n="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$n" in
    *.jar)                                 echo JVM ;;
    *windows*|*.exe|*.msi)                 echo WIN ;;
    *.apk|*android*)                       echo APK ;;
    *ios*|*iphone*|*ipad*|*.ipa)           echo IOS ;;
    *harmony*|*hmos*|*.hap)                echo HMY ;;
    *linux*|*.deb)                         echo LNX ;;
    *)                                     echo "" ;;
  esac
}

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

entries=""
sep=""
count=0
total=0
for f in "$FILES_DIR"/*; do
  [ -f "$f" ] || continue
  base="$(basename "$f")"
  case "$base" in
    version.json|README.md|*.txt|.*) continue ;;
  esac
  plat="$(platform_of "$base")"
  if [ -z "$plat" ]; then
    echo "跳过无法识别平台的产物：$base" >&2
    continue
  fi
  art="client"; [ "$plat" = "JVM" ] && art="probe"

  size="$(wc -c < "$f" | tr -d ' ')"
  sha="$(hash_of "$f")"
  count=$((count + 1))
  total=$((total + size))

  entries="$entries$sep  {
    \"platform\": \"$plat\",
    \"artifact\": \"$art\",
    \"version\": \"$(json_escape "$VERSION")\",
    \"file\": \"$(json_escape "$base")\",
    \"size_bytes\": $size,
    \"sha256\": \"$sha\"
  }"
  sep=",
"
  printf '  %-46s %12s B  %s\n' "$base" "$size" "$sha" >&2
done

json="[
$entries
]"

if [ -n "$OUT" ]; then
  printf '%s\n' "$json" > "$OUT"
  echo "已写入 $OUT（$count 个产物，合计 $total B）" >&2
else
  printf '%s\n' "$json"
fi