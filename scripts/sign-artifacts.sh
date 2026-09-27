#!/usr/bin/env bash
# PACC 发布件数字签名（Alpha 1.0.0）
#
# 用法：
#   ./scripts/sign-artifacts.sh 5.4.0
#   ./scripts/sign-artifacts.sh 5.4.0 --verify      # 只做验签，不签名
#
# 证书一律走环境变量，不进仓库：
#   Windows  PACC_WIN_CERT_PFX / PACC_WIN_CERT_PASSWORD   （EV 代码签名证书）
#   Android  PACC_ANDROID_KEYSTORE / PACC_ANDROID_KEY_ALIAS / PACC_ANDROID_KEYSTORE_PASSWORD
#   JAR      PACC_JAR_KEYSTORE / PACC_JAR_KEY_ALIAS / PACC_JAR_KEYSTORE_PASSWORD
#   iOS      macOS 上由 Xcode 自动处理（钥匙串里的 Apple Developer 证书）
#   HarmonyOS PACC_HARMONY_PROFILE / PACC_HARMONY_KEYSTORE
#
# 缺哪套证书就跳过哪个平台，并打印 [跳过] 原因 —— 签名是发布阻断项，
# 不能因为「本地没证书」就让脚本静默地当作已签名。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
MODE="${2:-}"

if [ -z "$VERSION" ]; then
  echo "用法: $0 <版本号> [--verify]" >&2
  exit 2
fi

BUILD_DIR="dist/${VERSION}"
if [ ! -d "$BUILD_DIR" ]; then
  echo "产物目录不存在：${BUILD_DIR}（先跑 ./scripts/build-all.sh）" >&2
  exit 2
fi

have() { command -v "$1" >/dev/null 2>&1; }
skip() { printf '  [跳过] %s：%s\n' "$1" "$2"; }
ok() { printf '  [完成] %s\n' "$1"; }

# ------------------------------------------------------------ Windows
sign_windows() {
  local files
  files="$(find "$BUILD_DIR" -maxdepth 1 -iname '*.exe' -o -maxdepth 1 -iname '*.dll' -o -maxdepth 1 -iname '*.sys' 2>/dev/null || true)"
  if [ -z "$files" ]; then
    skip "Windows 签名" "${BUILD_DIR} 下没有 exe/dll/sys"
    return 0
  fi

  if ! have signtool; then
    skip "Windows 签名" "未找到 signtool（需 Windows SDK）"
    return 0
  fi

  if [ "$MODE" = "--verify" ]; then
    while IFS= read -r f; do
      signtool verify /pa /v "$f" && ok "验签通过：$(basename "$f")"
    done <<<"$files"
    return 0
  fi

  if [ -z "${PACC_WIN_CERT_PFX:-}" ] || [ -z "${PACC_WIN_CERT_PASSWORD:-}" ]; then
    skip "Windows 签名" "未配置 PACC_WIN_CERT_PFX / PACC_WIN_CERT_PASSWORD"
    return 0
  fi

  while IFS= read -r f; do
    # /fd sha256 + RFC3161 时间戳：没有时间戳的签名在证书到期后即失效
    signtool sign /f "$PACC_WIN_CERT_PFX" /p "$PACC_WIN_CERT_PASSWORD" \
      /tr http://timestamp.digicert.com /td sha256 /fd sha256 "$f"
    ok "已签名：$(basename "$f")"
  done <<<"$files"

  if find "$BUILD_DIR" -maxdepth 1 -iname '*.sys' | grep -q .; then
    echo "  注意：内核驱动 .sys 需 WHQL 认证才能被 64 位 Windows 正式加载，"
    echo "        Alpha 阶段可先用 bcdedit /set testsigning on 的测试签名环境。"
  fi
}

# ------------------------------------------------------------ Android
sign_android() {
  local apk="$BUILD_DIR/pacc-android-${VERSION}.apk"
  [ -f "$apk" ] || { skip "Android 签名" "未找到 $apk"; return 0; }

  local apksigner="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools"
  apksigner="$(find "$apksigner" -maxdepth 2 -name 'apksigner*' 2>/dev/null | sort | tail -n1 || true)"

  if [ -z "$apksigner" ]; then
    skip "Android 签名" "未找到 apksigner（需 Android build-tools）"
    return 0
  fi

  if [ "$MODE" = "--verify" ]; then
    "$apksigner" verify --verbose "$apk" && ok "验签通过：$(basename "$apk")"
    return 0
  fi

  if [ -z "${PACC_ANDROID_KEYSTORE:-}" ]; then
    skip "Android 签名" "未配置 PACC_ANDROID_KEYSTORE"
    return 0
  fi

  # v4 要求同时开 v2/v3，否则 Android 11+ 只认 v4 的增量安装会退化
  "$apksigner" sign \
    --ks "$PACC_ANDROID_KEYSTORE" \
    --ks-key-alias "${PACC_ANDROID_KEY_ALIAS:-pacc}" \
    --ks-pass "pass:${PACC_ANDROID_KEYSTORE_PASSWORD:?}" \
    --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled true \
    "$apk"
  ok "已签名（v2/v3/v4）：$(basename "$apk")"
}

# ------------------------------------------------------------ JAR
sign_jar() {
  local jar="$BUILD_DIR/pacc-client-${VERSION}.jar"
  local agent="$BUILD_DIR/pacc-javaagent-${VERSION}.jar"

  if [ "$MODE" = "--verify" ]; then
    for f in "$jar" "$agent"; do
      [ -f "$f" ] || continue
      if jarsigner -verify "$f" >/dev/null 2>&1; then ok "验签通过：$(basename "$f")"; else echo "  [未签名] $(basename "$f")"; fi
    done
    return 0
  fi

  if ! have jarsigner; then
    skip "JAR 签名" "未找到 jarsigner"
    return 0
  fi
  if [ -z "${PACC_JAR_KEYSTORE:-}" ]; then
    skip "JAR 签名" "未配置 PACC_JAR_KEYSTORE（可选增强，后端 JAR 本就不签名）"
    return 0
  fi

  # 签名后必须重新计算校验和，否则 SHA256SUMS 与产物对不上
  for f in "$jar" "$agent"; do
    [ -f "$f" ] || continue
    jarsigner -keystore "$PACC_JAR_KEYSTORE" \
      -storepass "${PACC_JAR_KEYSTORE_PASSWORD:?}" \
      -keypass "${PACC_JAR_KEYSTORE_PASSWORD}" \
      "${PACC_JAR_KEY_ALIAS:-pacc}" "$f"
    ok "已签名：$(basename "$f")"
  done
  echo "  提示：JAR 签名改动了文件内容，请重跑 ./scripts/build-all.sh 的校验和步骤。"
}

# ------------------------------------------------------------ iOS / HarmonyOS
sign_ios() {
  local ipa="$BUILD_DIR/pacc-ios-${VERSION}.ipa"
  [ -f "$ipa" ] || { skip "iOS 签名" "未找到 $ipa（IPA 由 .github/workflows/ios-build.yml 在 macOS 上产出）"; return 0; }
  if [ "$(uname -s)" != "Darwin" ]; then
    skip "iOS 签名" "codesign 只能在 macOS 上执行"
    return 0
  fi
  if [ "$MODE" = "--verify" ]; then
    codesign --verify --deep --strict --verbose=2 "$ipa" && ok "验签通过：$(basename "$ipa")"
    return 0
  fi
  # iOS 签名与描述文件绑定，工程内已配置，交给 xcodebuild -exportArchive 处理
  skip "iOS 签名" "由 Xcode 归档导出流程处理（见 ios-build.yml）"
}

sign_harmony() {
  local hap="$BUILD_DIR/pacc-harmony-${VERSION}.hap"
  [ -f "$hap" ] || { skip "HarmonyOS 签名" "未找到 $hap"; return 0; }
  if [ -z "${PACC_HARMONY_PROFILE:-}" ]; then
    skip "HarmonyOS 签名" "未配置 PACC_HARMONY_PROFILE / PACC_HARMONY_KEYSTORE"
    return 0
  fi
  if ! have hap-sign-tool; then
    skip "HarmonyOS 签名" "未找到 hap-sign-tool（DevEco SDK 提供）"
    return 0
  fi
  hap-sign-tool sign-app \
    -keyAlias "${PACC_HARMONY_KEY_ALIAS:-pacc}" \
    -signAlg SHA256withECDSA \
    -mode localSign \
    -appCertFile "$PACC_HARMONY_PROFILE" \
    -profileFile "$PACC_HARMONY_PROFILE" \
    -inFile "$hap" -outFile "$hap"
  ok "已签名：$(basename "$hap")"
}

mode_label="签名"
[ "$MODE" = "--verify" ] && mode_label="验签"
echo "== PACC ${VERSION} 发布件${mode_label} =="
echo "-- Windows（Authenticode / WHQL）--"; sign_windows
echo "-- Android（APK Signature v2/v3/v4）--"; sign_android
echo "-- Java JAR（jarsigner，可选）--"; sign_jar
echo "-- iOS（App Store / Ad Hoc）--"; sign_ios
echo "-- HarmonyOS（AppGallery）--"; sign_harmony
echo
echo "已签名的产物需要重算校验和：cd ${BUILD_DIR} && sha256sum ./* >SHA256SUMS.txt"