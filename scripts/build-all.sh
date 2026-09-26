#!/usr/bin/env bash
# PACC DF Alpha 1.0.0 一键构建流水线
#
# 用法：
#   ./scripts/build-all.sh                 # 全量构建（缺工具链的步骤自动跳过）
#   ./scripts/build-all.sh --only java,frontend
#   ./scripts/build-all.sh --version 5.4.0
#   ./scripts/build-all.sh --with-tests    # 顺带跑测试（默认 -DskipTests 提速）
#
# 产物：dist/<version>/ 下的发布件 + SHA256SUMS.txt
#
# 关于「跳过」：Android 要 SDK+Gradle、iOS 要 Xcode、HarmonyOS 要 DevEco，
# 这三样没法在同一个 CI runner 上凑齐，所以脚本按工具链探测结果决定构建或跳过，
# 跳过一律打印 [跳过] 与原因，不会静默产出缺失的包。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION=""
ONLY=""
RUN_TESTS="no"

while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --only)
      ONLY="${2:-}"
      shift 2
      ;;
    --with-tests)
      RUN_TESTS="yes"
      shift
      ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  # 版本以 Maven 后端为准：它是发布链路的源头，其他模块都要跟它对齐
  VERSION="$(perl -0ne 'print $1 if m{<artifactId>ptv-backend</artifactId>\s*<version>([^<]+)}' ptv-backend/pom.xml)"
fi
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "无法确定版本号（读到：$VERSION），请用 --version 指定" >&2
  exit 2
fi

BUILD_DIR="dist/${VERSION}"
mkdir -p "$BUILD_DIR"

MVN_FLAGS="-B"
if [ "$RUN_TESTS" = "no" ]; then
  MVN_FLAGS="$MVN_FLAGS -DskipTests"
fi

# want <步骤名>：--only 过滤。空 --only 表示全部要。
want() {
  [ -z "$ONLY" ] && return 0
  case ",$ONLY," in
    *",$1,"*) return 0 ;;
    *) return 1 ;;
  esac
}

# have <命令>：工具链探测
have() {
  command -v "$1" >/dev/null 2>&1
}

step() {
  printf '\n\033[1m[%s] %s\033[0m\n' "$1" "$2"
}

skip() {
  printf '  [跳过] %s：%s\n' "$1" "$2"
}

ok() {
  printf '  [完成] %s\n' "$1"
}

echo "================================================"
echo " PACC DF Alpha 1.0.0 构建流水线"
echo " 版本     : ${VERSION}"
echo " 构建时间 : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo " 产物目录 : ${BUILD_DIR}"
echo "================================================"

# ---------------------------------------------------------------- 1. 版本统一
step 1/9 "统一版本号"
./scripts/bump-version.sh "$VERSION" >/dev/null
ok "全仓库版本号已对齐到 ${VERSION}"

# ---------------------------------------------------------------- 2. Maven 模块
if want java; then
  step 2/9 "构建 Java 模块（协议运行时 / 更新核心 / 后端 / 玩家端 / Java 探针）"
  if have mvn; then
    # 仓库没有 root 聚合 pom，每个模块独立 mvn。协议运行时 pacc-binary-protocol 只在本仓库里，
    # 没有发布到中央仓库，所以必须先 install 进本地仓库，否则后面三个模块会以
    # 「无法解析 com.potatotv:pacc-binary-protocol」直接失败（CI 的 java job 同此顺序）。
    # 目录还不存在时（该模块尚未入库）不能硬失败：后端/玩家端此时的 pom 也还没依赖它，
    # 直接跳过即可，否则一条干净的 clone 会在这里被 set -e 掐断。
    if [ -d pacc-binary-protocol/runtime-java ]; then
      ( cd pacc-binary-protocol/runtime-java && mvn $MVN_FLAGS -q install )
      ok "pacc-binary-protocol（已装入本地仓库）"
    else
      skip "pacc-binary-protocol" "目录不存在（模块尚未入库），按本地仓库现有版本解析"
    fi

    # 更新核心同理：它也只在本仓库里，而 ptv-client 要依赖它
    if [ -d pacc-cross-platform-updater ]; then
      ( cd pacc-cross-platform-updater && mvn $MVN_FLAGS -q install )
      ok "pacc-cross-platform-updater（已装入本地仓库）"
    else
      skip "pacc-cross-platform-updater" "目录不存在（模块尚未入库）"
    fi

    ( cd ptv-backend && mvn $MVN_FLAGS -q clean package )
    cp "ptv-backend/target/ptv-backend-${VERSION}.jar" "${BUILD_DIR}/pacc-backend-${VERSION}.jar"
    ok "pacc-backend-${VERSION}.jar"

    ( cd ptv-client && mvn $MVN_FLAGS -q clean package )
    cp "ptv-client/target/ptv-client-${VERSION}.jar" "${BUILD_DIR}/pacc-client-${VERSION}.jar"
    ok "pacc-client-${VERSION}.jar"

    ( cd platform/java-agent && mvn $MVN_FLAGS -q clean package )
    cp "platform/java-agent/target/ptv-agent-${VERSION}.jar" "${BUILD_DIR}/pacc-javaagent-${VERSION}.jar"
    ok "pacc-javaagent-${VERSION}.jar"
  else
    skip "Java 模块" "未找到 mvn"
  fi
fi

# ---------------------------------------------------------------- 3. 管理端前端
if want frontend; then
  step 3/9 "构建管理端前端"
  if have npm; then
    ( cd ptv-frontend && npm ci --silent && npm run build --silent )
    tar -czf "${BUILD_DIR}/pacc-admin-${VERSION}.tar.gz" -C ptv-frontend dist
    ok "pacc-admin-${VERSION}.tar.gz"
  else
    skip "管理端前端" "未找到 npm"
  fi
fi

# ---------------------------------------------------------------- 4. Windows GUI
if want windows; then
  step 4/9 "构建 Windows GUI"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      if have dotnet; then
        ( cd tools/windows-gui && dotnet publish -c Release -p:Version="$VERSION" -o "../../dist/win-x64" --nologo -v q )
        # 安装包用整目录自包含发布（见 tools/installer/pacc-client-installer.iss 的 DistDir）
        cp "dist/win-x64/PaccManager.exe" "${BUILD_DIR}/pacc-gui-${VERSION}.exe"
        ok "pacc-gui-${VERSION}.exe"
        if have ISCC || [ -x "/c/Program Files (x86)/Inno Setup 6/ISCC.exe" ]; then
          ISCC_BIN="$(command -v ISCC || echo '/c/Program Files (x86)/Inno Setup 6/ISCC.exe')"
          ( cd tools/installer && "$ISCC_BIN" -DMyAppVersion="$VERSION" pacc-client-installer.iss >/dev/null )
          cp "tools/installer/Output/PACCClientSetup-${VERSION}.exe" "${BUILD_DIR}/pacc-setup-${VERSION}.exe"
          ok "pacc-setup-${VERSION}.exe"
        else
          skip "Windows 安装包" "未找到 Inno Setup 的 ISCC"
        fi
      else
        skip "Windows GUI" "未找到 dotnet"
      fi
      ;;
    *)
      skip "Windows GUI" "当前平台 $(uname -s) 无法构建 Windows 产物"
      ;;
  esac
fi

# ---------------------------------------------------------------- 5. Android
if want android; then
  step 5/9 "构建 Android APK"
  if have java && [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    ( cd ptv-mobile/android && ./gradlew --no-daemon assembleRelease )
    cp "ptv-mobile/android/app/build/outputs/apk/release/app-release.apk" \
       "${BUILD_DIR}/pacc-android-${VERSION}.apk"
    ok "pacc-android-${VERSION}.apk"
  else
    skip "Android APK" "缺少 Android SDK（ANDROID_HOME / ANDROID_SDK_ROOT）"
  fi
fi

# ---------------------------------------------------------------- 6. iOS / HarmonyOS
if want ios; then
  step 6/9 "构建 iOS IPA"
  if [ "$(uname -s)" = "Darwin" ]; then
    skip "iOS IPA" "需要 Xcode 工程签名配置（见 platform/ios/README.md），改由 ios-build.yml 工作流产出"
  else
    skip "iOS IPA" "只能在 macOS 上构建"
  fi
fi

if want harmony; then
  if have hvigorw || have hvigor; then
    step "6b" "构建 HarmonyOS HAP"
    ( cd platform/harmony && hvigorw assembleHap --no-daemon )
    ok "HarmonyOS HAP（产物路径见 platform/harmony/README.md）"
  else
    skip "HarmonyOS HAP" "未找到 DevEco 的 hvigor"
  fi
fi

# ---------------------------------------------------------------- 7. Linux 原生端
if want linux; then
  step 7/9 "构建 Linux 原生端（检测核心 + eBPF + systemd）"
  case "$(uname -s)" in
    Linux)
      if have cargo; then
        ( cd platform/linux-client && cargo build --release )
        # eBPF 的构造前提比客户端本身宽（clang + libbpf + bpftool + 与内核匹配的 BTF）。
        # 缺工具链时 make 会失败——那不该拖垮整条流水线：客户端内置 procfs 降级路径，
        # 没有 eBPF 目标文件依然可用（见 platform/kernel-linux/README.md 的兼容性矩阵）。
        # 但必须打印出来，不能静默出一个「看起来完整」的包。
        if ( cd platform/kernel-linux && make ) >/dev/null 2>&1; then
          ok "eBPF 目标文件"
        else
          skip "eBPF 目标文件" "构建失败（通常缺 clang/libbpf/bpftool 或内核 BTF），客户端将以 procfs 模式降级"
        fi
        ( cd platform/linux-client && ./package.sh "$VERSION" )
        cp "platform/linux-client/target/dist/pacc-linux-${VERSION}.tar.gz" "${BUILD_DIR}/"
        ok "pacc-linux-${VERSION}.tar.gz"
      else
        skip "Linux 原生端" "未找到 cargo"
      fi
      ;;
    *)
      skip "Linux 原生端" "只能在 Linux 上构建（eBPF/CO-RE 需目标内核头文件）"
      ;;
  esac
fi

# ---------------------------------------------------------------- 8. 服务侧组件
if want services; then
  step 8/9 "构建服务侧组件（Rust 管道 / Go 网关 / AI 服务）"
  if have cargo; then
    ( cd deploy/pipe-rust && cargo build --release --quiet )
    ok "pacc-pipe（deploy/pipe-rust/target/release/）"
  else
    skip "Rust 管道" "未找到 cargo"
  fi

  if have go; then
    ( cd deploy/gateway-go && go build -trimpath -ldflags="-s -w" -o gateway-go . )
    ok "gateway-go"
  else
    skip "Go 网关" "未找到 go"
  fi
fi

# ---------------------------------------------------------------- 9. 校验和
step 9/9 "计算 SHA256 校验和"
(
  cd "${BUILD_DIR}"
  # 先清掉上一轮遗留的校验和文件，否则会被当成待校验对象递归进列表
  rm -f SHA256SUMS.txt
  if have sha256sum; then
    sha256sum ./* >SHA256SUMS.txt
  else
    shasum -a 256 ./* >SHA256SUMS.txt
  fi
)
ok "SHA256SUMS.txt"

echo
echo "================================================"
echo " 构建完成。产物清单："
ls -lh "${BUILD_DIR}"
echo "================================================"
echo "发布前还需：./scripts/sign-artifacts.sh ${VERSION}（签名，缺证书会跳过对应平台）"