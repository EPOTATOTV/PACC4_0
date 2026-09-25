#!/usr/bin/env bash
# PACC 版本号统一脚本 —— 把全仓库的「产品版本号」字段收敛到同一个值。
#
# 用法：
#   ./scripts/bump-version.sh 5.4.0            # 写入
#   ./scripts/bump-version.sh 5.4.0 --check    # 只校验，存在不一致则退出码 2
#
# 设计要点：
#   1) 只改「版本字段」，不动依赖版本号。pom 里 <version> 出现十几次，
#      所以每个替换都用 artifactId / 键名 / 上一行上下文锚定，避免误伤。
#   2) 幂等：重复执行不产生额外改动。
#   3) 缺文件只告警不中断。iOS 工程、下载站等分支上可能被裁剪。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NEW="${1:-}"
MODE="${2:-}"

if [ -z "$NEW" ]; then
  echo "用法: $0 <版本号 x.y.z> [--check]" >&2
  exit 2
fi
if ! printf '%s' "$NEW" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号必须是 x.y.z 形式，收到：$NEW" >&2
  exit 2
fi
if [ -n "$MODE" ] && [ "$MODE" != "--check" ]; then
  echo "未知参数：$MODE（只支持 --check）" >&2
  exit 2
fi

export PACC_NEW_VERSION="$NEW"

CHANGED=0
MISSING=0

# sub <相对路径> <perl 程序>
#   perl 程序里用 $ENV{PACC_NEW_VERSION} 取目标版本；替换表达式用 s///e 形式。
sub() {
  local file="$1" prog="$2"

  if [ ! -f "$file" ]; then
    printf '  [缺失] %s\n' "$file"
    MISSING=$((MISSING + 1))
    return 0
  fi

  if [ "$MODE" = "--check" ]; then
    local tmp
    tmp="$(mktemp)"
    cp "$file" "$tmp"
    perl -0pi -e "$prog" "$tmp"
    if cmp -s "$file" "$tmp"; then
      printf '  [一致] %s\n' "$file"
    else
      printf '  [不一致] %s\n' "$file"
      CHANGED=$((CHANGED + 1))
    fi
    rm -f "$tmp"
    return 0
  fi

  local before after
  before="$(cksum <"$file")"
  perl -0pi -e "$prog" "$file"
  after="$(cksum <"$file")"
  if [ "$before" = "$after" ]; then
    printf '  [一致] %s\n' "$file"
  else
    printf '  [更新] %s\n' "$file"
    CHANGED=$((CHANGED + 1))
  fi
}

echo "== PACC 版本统一：目标 $NEW（模式 ${MODE:-write}）=="

echo "-- Maven 模块（锚定 artifactId，避开依赖版本）--"
sub ptv-backend/pom.xml \
  's{(<artifactId>ptv-backend</artifactId>\s*<version>)[^<]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-client/pom.xml \
  's{(<artifactId>ptv-client</artifactId>\s*<version>)[^<]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub platform/java-agent/pom.xml \
  's{(<artifactId>ptv-agent</artifactId>\s*<version>)[^<]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- 后端运行时版本号 --"
sub ptv-backend/src/main/resources/application.yml \
  's{(title: PACC PTV 管控后端 API\s*\n\s*version:\s*)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-backend/src/main/java/com/potatotv/pacc/controller/SystemController.java \
  's{(APP_VERSION\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- 玩家端与探针 --"
sub ptv-client/src/main/java/com/potatotv/paccclient/PaccClient.java \
  's{(APP_VERSION\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub platform/java-agent/src/main/java/com/potatotv/pacc/agent/PaccJavaAgent.java \
  's{(VERSION\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
# 上报报文里的端侧版本号与 User-Agent：后端按版本分流灰度，写死会一直把自己报成旧端
sub platform/java-agent/src/main/java/com/potatotv/pacc/agent/ReportServer.java \
  's{(body\.put\("version",\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-client/src/main/java/com/potatotv/paccclient/PtvAuth.java \
  's{(PACC-PlayerClient/)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}se'

# 签名库版本随客户端版本一起走：端侧请求的库版本必须和后端默认库版本对得上，
# 否则特征库热更新会因为版本号不匹配而拿不到包
sub ptv-client/src/main/resources/pacc-client.properties \
  's{(pacc\.client\.signature\.version=v)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-client/src/main/java/com/potatotv/paccclient/ClientConfig.java \
  's{(PACC_CLIENT_SIGNATURE_VERSION",\s*"v)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-backend/src/main/java/com/potatotv/pacc/bootstrap/DataSeeder.java \
  's{("v)5\.0\.0(",\s*"system-seed")}{$1 . $ENV{PACC_NEW_VERSION} . $2}se'
sub ptv-backend/src/main/java/com/potatotv/pacc/service/SignatureLibraryService.java \
  's{("v)5\.0\.0}{$1 . $ENV{PACC_NEW_VERSION}}ge'

echo "-- 管理端前端 --"
sub ptv-frontend/package.json \
  's{("version"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
# package-lock 头部两处（lockfile 自身 + packages[""]）与 package.json 对应依赖；
# 后面成百上千条依赖版本不能碰，所以只改前两处。
sub ptv-frontend/package-lock.json \
  's{("version"\s*:\s*")[^"]+}{++$n <= 2 ? $1 . $ENV{PACC_NEW_VERSION} : $&}ge'
sub ptv-frontend/src/pages/player/PlayerDownload.tsx \
  's{5\.0\.0}{$ENV{PACC_NEW_VERSION}}ge'
sub ptv-frontend/src/pages/player/PlayerDiagnostics.tsx \
  's{v5\.0\.0}{"v" . $ENV{PACC_NEW_VERSION}}ge'
sub ptv-frontend/src/pages/SignatureLibrary.tsx \
  's{v5\.0\.0}{"v" . $ENV{PACC_NEW_VERSION}}ge'

echo "-- Windows GUI 与安装包 --"
sub tools/windows-gui/PaccManager.csproj \
  's{(<Version>)[^<]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
# 这两个文件里出现的 5.0.0 全部是版本号，没有别的数字会误伤，直接整体替换
sub tools/windows-gui/build-client.ps1 \
  's{5\.0\.0}{$ENV{PACC_NEW_VERSION}}ge'
sub tools/windows-gui/deploy/installer.ps1 \
  's{5\.0\.0}{$ENV{PACC_NEW_VERSION}}ge'
# GUI 按名字找探针 jar，这里改了名却不同步就会出现「探针文件缺失」的假告警
sub tools/windows-gui/MainWindow.xaml.cs \
  's{ptv-agent-5\.0\.0\.jar}{"ptv-agent-" . $ENV{PACC_NEW_VERSION} . ".jar"}ge'
sub tools/installer/pacc-client-installer.iss \
  's{(#define MyAppVersion ")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub tools/installer/pacc-client-installer.iss \
  's{ptv-agent-5\.0\.0\.jar}{"ptv-agent-" . $ENV{PACC_NEW_VERSION} . ".jar"}ge'
sub tools/installer/pacc-client-installer.iss \
  's{PACCClientSetup-5\.0\.0\.exe}{"PACCClientSetup-" . $ENV{PACC_NEW_VERSION} . ".exe"}ge'

echo "-- 内核模块与 AI 服务 --"
sub platform/kernel-linux/pacc_ldm.c \
  's{(MODULE_VERSION\(")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub deploy/ai/app/main.py \
  's{("version": ")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- 移动端（Android / iOS）--"
# versionName 按语义化版本；versionCode 必须单调递增，用 major*10000+minor*100+patch 映射。
sub ptv-mobile/android/app/build.gradle \
  's{(versionName\s+")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-mobile/android/app/build.gradle \
  's{(versionCode\s+)\d+}{my @p = split /\./, $ENV{PACC_NEW_VERSION}; $1 . ($p[0] * 10000 + $p[1] * 100 + $p[2])}se'
sub ptv-mobile/ios/App/App.xcodeproj/project.pbxproj \
  's{(MARKETING_VERSION = )[0-9][0-9.]*;}{$1 . $ENV{PACC_NEW_VERSION} . ";"}ge'
sub ptv-mobile/package.json \
  's{("version"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-mobile/package-lock.json \
  's{("version"\s*:\s*")[^"]+}{++$n <= 2 ? $1 . $ENV{PACC_NEW_VERSION} : $&}ge'

echo "-- 桌面端 --"
sub ptv-desktop/package.json \
  's{("version"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-desktop/package-lock.json \
  's{("version"\s*:\s*")[^"]+}{++$n <= 2 ? $1 . $ENV{PACC_NEW_VERSION} : $&}ge'
sub ptv-desktop/src-tauri/tauri.conf.json \
  's{("version"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-desktop/src-tauri/Cargo.toml \
  's{(?m)^(version\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub ptv-desktop/src-tauri/Cargo.lock \
  's{(name = "pacc-desktop"\s*\nversion\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- HarmonyOS --"
sub platform/harmony/oh-package.json5 \
  's{("version"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- Rust 管道 --"
sub deploy/pipe-rust/Cargo.toml \
  's{(?m)^(version\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub deploy/pipe-rust/Cargo.lock \
  's{(name = "pacc-pipe"\s*\nversion\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- AI 推理服务 --"
sub deploy/ai/app/main.py \
  's{(version=")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- 下载站 --"
# version.json 里的 min_version 表示「后端最低兼容的客户端版本」。
# v5.4 后端与旧版客户端不兼容，所以这里必须同步抬高，否则下载站会把旧端放进来。
sub deploy/dl-web/files/version.json \
  's{5\.0\.0}{$ENV{PACC_NEW_VERSION}}ge'
sub deploy/dl-web/js/config.js \
  's{(PACC_VERSION\s*=\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub deploy/dl-web/index.html \
  's{("softwareVersion"\s*:\s*")[^"]+}{$1 . $ENV{PACC_NEW_VERSION}}se'
sub deploy/dl-web/index.html \
  's{(class="version mono">v)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}se'

echo "-- 容器镜像标签 --"
sub docker-compose.yml \
  's{(image:\s*pacc/[a-z0-9-]+:)[0-9][0-9.]*}{$1 . $ENV{PACC_NEW_VERSION}}ge'

echo
printf '完成：%d 处改动，%d 个文件缺失\n' "$CHANGED" "$MISSING"

if [ "$MODE" = "--check" ] && [ "$CHANGED" -gt 0 ]; then
  echo "存在版本号不一致，未通过校验。" >&2
  exit 2
fi