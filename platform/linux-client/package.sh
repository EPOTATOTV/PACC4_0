#!/usr/bin/env bash
# PACC Linux 原生客户端打包
#
# 用法：
#   ./package.sh 5.4.0                 # 版本号作为第一个位置参数（build-all.sh 的调用形式）
#   ./package.sh                       # 不给参数时从 Cargo.toml 读，读不到则用 5.4.0
#   ./package.sh 5.4.0 --no-build      # 跳过 cargo build（复用已有产物）
#
# 产物（固定路径，scripts/build-all.sh 第 7 步依赖）：
#   target/dist/pacc-linux-<version>.tar.gz     —— 始终产出
#   target/dist/pacc-linux-<version>.deb        —— 有 dpkg-deb 时产出
#   target/dist/pacc-linux-<version>.rpm        —— 有 rpmbuild 时产出
#
# 缺 dpkg-deb / rpmbuild 时打印原因并跳过对应格式，**不算失败**：
# 这两个工具在纯 CI runner 上常常没有，不该因此卡住整条发布流水线。
# 真正的失败（构建失败、二进制缺失）一律非零退出，让 build-all.sh 的 set -e 拦住。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 兜底版本号：与 Cargo.toml 的 [package] version 保持同步。
DEFAULT_VERSION="5.4.0"

VERSION=""
DO_BUILD="yes"

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) DO_BUILD="no"; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    -*) echo "未知参数：$1" >&2; exit 2 ;;
    *) VERSION="$1"; shift ;;
  esac
done

# 没给位置参数就从 Cargo.toml 的 [package] 段读版本；读不到再用兜底值。
# 这样 `./package.sh` 单独跑也能产出正确文件名，不至于打出个空版本号的包。
if [ -z "$VERSION" ]; then
  VERSION="$(sed -n 's/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' Cargo.toml | head -1)"
  if [ -n "$VERSION" ]; then
    echo "未指定版本号，从 Cargo.toml 读到：${VERSION}"
  else
    VERSION="$DEFAULT_VERSION"
    echo "未指定版本号且 Cargo.toml 未读到，回退到默认：${VERSION}" >&2
  fi
fi
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号必须是 x.y.z 形式，读到：${VERSION}" >&2
  exit 2
fi

PKG_NAME="pacc-linux-${VERSION}"
DIST_DIR="${SCRIPT_DIR}/target/dist"
STAGE_ROOT="${DIST_DIR}/staging"
STAGE="${STAGE_ROOT}/${PKG_NAME}"
BIN_SRC="${SCRIPT_DIR}/target/release/pacc-linux-client"

ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
  x86_64|amd64) ARCH_DEB="amd64"; ARCH_RPM="x86_64" ;;
  aarch64|arm64) ARCH_DEB="arm64"; ARCH_RPM="aarch64" ;;
  armv7l) ARCH_DEB="armhf"; ARCH_RPM="armv7hl" ;;
  *) ARCH_DEB="$ARCH_RAW"; ARCH_RPM="$ARCH_RAW" ;;
esac

step() { printf '\n\033[1m[%s]\033[0m\n' "$1"; }
skip() { printf '  [跳过] %s：%s\n' "$1" "$2"; }
ok() { printf '  [完成] %s\n' "$1"; }

have() { command -v "$1" >/dev/null 2>&1; }

echo "================================================"
echo " PACC Linux 客户端打包"
echo " 版本   : ${VERSION}"
echo " 架构   : ${ARCH_RAW} (deb=${ARCH_DEB} rpm=${ARCH_RPM})"
echo " 产物   : ${DIST_DIR}"
echo "================================================"

# ------------------------------------------------------------------ 1. 构建
step 1/5 "构建 release 二进制"

if [ "$DO_BUILD" = "yes" ]; then
  if have cargo; then
    cargo build --release
    ok "cargo build --release"
  else
    echo "未找到 cargo，且未指定 --no-build。请先安装 Rust 工具链。" >&2
    exit 1
  fi
else
  skip "构建" "按 --no-build 复用已有产物"
fi

if [ ! -f "$BIN_SRC" ]; then
  echo "缺少 ${BIN_SRC}，无法打包。" >&2
  exit 1
fi

# ------------------------------------------------------------------ 2. 暂存树
step 2/5 "组装暂存目录"

rm -rf "$STAGE"
install -d -m 0755 "$STAGE/bin"
install -m 0755 "$BIN_SRC" "$STAGE/bin/pacc-linux-client"

# 随包附带安装所需的一切：单元、示例配置、安装脚本、说明。
for f in pacc-client.service pacc-client.properties.example install.sh README.md; do
  if [ -f "${SCRIPT_DIR}/${f}" ]; then
    install -m 0644 "${SCRIPT_DIR}/${f}" "$STAGE/$f"
  else
    echo "  [警告] 缺少 ${f}，未打进包" >&2
  fi
done

# 让解压后的 install.sh 能默认找到 bin/ 下的二进制（不依赖 target/release）。
cat >"$STAGE/BUILD-INFO" <<EOF
name=pacc-linux-client
version=${VERSION}
arch=${ARCH_RAW}
built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
rustc=$(rustc --version 2>/dev/null || echo unknown)
note=install.sh 默认找 ./target/release/pacc-linux-client；解压包里请用 --binary ./bin/pacc-linux-client
EOF
ok "暂存到 ${STAGE}"
ls -1 "$STAGE" | sed 's/^/    /'

# ------------------------------------------------------------------ 3. tar.gz
step 3/5 "生成通用压缩包"

mkdir -p "$DIST_DIR"
TARBALL="${DIST_DIR}/${PKG_NAME}.tar.gz"
tar -czf "$TARBALL" -C "$STAGE_ROOT" "$PKG_NAME"
ok "${TARBALL}"

# ------------------------------------------------------------------ 4. deb
step 4/5 "生成 Debian 包（可选）"

if have dpkg-deb; then
  DEB_ROOT="${DIST_DIR}/deb/${PKG_NAME}"
  rm -rf "$DEB_ROOT"
  install -d -m 0755 "$DEB_ROOT/DEBIAN"
  install -d -m 0755 "$DEB_ROOT/usr/local/bin"
  install -d -m 0755 "$DEB_ROOT/etc/systemd/system"
  install -d -m 0755 "$DEB_ROOT/etc/pacc"
  install -d -m 0755 "$DEB_ROOT/usr/share/doc/${PKG_NAME}"

  install -m 0755 "$BIN_SRC" "$DEB_ROOT/usr/local/bin/pacc-linux-client"
  install -m 0644 "${SCRIPT_DIR}/pacc-client.service" \
    "$DEB_ROOT/etc/systemd/system/pacc-client.service"
  install -m 0644 "${SCRIPT_DIR}/pacc-client.properties.example" \
    "$DEB_ROOT/etc/pacc/pacc-client.properties.example"
  install -m 0644 "${SCRIPT_DIR}/README.md" "$DEB_ROOT/usr/share/doc/${PKG_NAME}/README.md"

  cat >"$DEB_ROOT/DEBIAN/control" <<EOF
Package: pacc-linux-client
Version: ${VERSION}
Section: utils
Priority: optional
Architecture: ${ARCH_DEB}
Maintainer: Potatotv PACC <dev@potatotv.asia>
Depends: libc6
Description: PACC Linux native anti-cheat client
 Detect events on the Linux player host and report them to the PACC
 backend. Uses an eBPF loader socket when available and falls back to
 plain procfs scanning. Ships a systemd unit and a sample config.
EOF

  # postinst：建账号、放配置模板、reload。不自动 enable（装包不等于玩家授权常驻）。
  cat >"$DEB_ROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if ! id -u pacc >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin pacc || true
fi
install -d -m 0750 -o root -g pacc /etc/pacc
if [ ! -f /etc/pacc/pacc-client.properties ]; then
    install -m 0640 -o root -g pacc \
        /etc/pacc/pacc-client.properties.example /etc/pacc/pacc-client.properties
fi
if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload || true
fi
exit 0
EOF
  chmod 0755 "$DEB_ROOT/DEBIAN/postinst"

  cat >"$DEB_ROOT/DEBIAN/prerm" <<'EOF'
#!/bin/sh
set -e
if command -v systemctl >/dev/null 2>&1; then
    systemctl stop pacc-client.service || true
    systemctl disable pacc-client.service || true
fi
exit 0
EOF
  chmod 0755 "$DEB_ROOT/DEBIAN/prerm"

  dpkg-deb --root-owner-group --build "$DEB_ROOT" "${DIST_DIR}/${PKG_NAME}.deb" >/dev/null
  ok "${DIST_DIR}/${PKG_NAME}.deb"
else
  skip "Debian 包" "未找到 dpkg-deb（Debian/Ubuntu 上 apt install dpkg 即可）"
fi

# ------------------------------------------------------------------ 5. rpm
step 5/5 "生成 RPM 包（可选）"

if have rpmbuild; then
  RPM_TOPDIR="${DIST_DIR}/rpmbuild"
  rm -rf "$RPM_TOPDIR"
  install -d -m 0755 "$RPM_TOPDIR"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}

  SPEC="${RPM_TOPDIR}/SPECS/pacc-linux-client.spec"
  cat >"$SPEC" <<EOF
Name:           pacc-linux-client
Version:        ${VERSION}
Release:        1
Summary:        PACC Linux native anti-cheat client
License:        Apache-2.0
BuildArch:      ${ARCH_RPM}
Requires:       glibc
# 二进制已 strip，关掉 debuginfo 生成，免得 brp-strip 在空 debug 上啰嗦。
%global debug_package %{nil}
%global _missing_build_ids_terminate_build 0

%description
Detect events on the Linux player host and report them to the PACC backend.
Uses an eBPF loader socket when available and falls back to plain procfs
scanning. Ships a systemd unit and a sample config.

%install
rm -rf %{buildroot}
install -d %{buildroot}%{_bindir}
install -d %{buildroot}%{_unitdir}
install -d %{buildroot}%{_sysconfdir}/pacc
install -m 0755 ${BIN_SRC} %{buildroot}%{_bindir}/pacc-linux-client
install -m 0644 ${SCRIPT_DIR}/pacc-client.service %{buildroot}%{_unitdir}/pacc-client.service
install -m 0644 ${SCRIPT_DIR}/pacc-client.properties.example %{buildroot}%{_sysconfdir}/pacc/pacc-client.properties.example

%post
if ! id -u pacc >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /sbin/nologin pacc || true
fi
if [ ! -f %{_sysconfdir}/pacc/pacc-client.properties ]; then
    install -m 0640 -o root -g pacc \
        %{_sysconfdir}/pacc/pacc-client.properties.example \
        %{_sysconfdir}/pacc/pacc-client.properties || true
fi
systemctl daemon-reload >/dev/null 2>&1 || true

%preun
if [ \$1 -eq 0 ]; then
    systemctl stop pacc-client.service >/dev/null 2>&1 || true
    systemctl disable pacc-client.service >/dev/null 2>&1 || true
fi

%files
%{_bindir}/pacc-linux-client
%{_unitdir}/pacc-client.service
%config(noreplace) %{_sysconfdir}/pacc/pacc-client.properties.example

%changelog
* $(LC_ALL=C date '+%a %b %d %Y') Potatotv PACC <dev@potatotv.asia> - ${VERSION}-1
- Automated build via package.sh
EOF

  rpmbuild --define "_topdir ${RPM_TOPDIR}" -bb "$SPEC" >/dev/null 2>&1
  RPM_BUILT="$(find "${RPM_TOPDIR}/RPMS" -name '*.rpm' -print -quit)"
  if [ -n "$RPM_BUILT" ]; then
    cp "$RPM_BUILT" "${DIST_DIR}/${PKG_NAME}.rpm"
    ok "${DIST_DIR}/${PKG_NAME}.rpm"
  else
    skip "RPM 包" "rpmbuild 执行未产出文件（看 ${RPM_TOPDIR} 排查）"
  fi
else
  skip "RPM 包" "未找到 rpmbuild（RHEL/CentOS 上 yum install rpm-build 即可）"
fi

# ------------------------------------------------------------------ 汇总
step "汇总"

echo " 产物清单："
ls -lh "$DIST_DIR" | sed 's/^/    /'
if command -v sha256sum >/dev/null 2>&1; then
  echo " SHA256："
  ( cd "$DIST_DIR" && sha256sum "${PKG_NAME}".* 2>/dev/null | sed 's/^/    /' ) || true
fi
echo
echo "================================================"
echo " 打包完成。核心产物：${TARBALL}"
echo " 安装：tar -xzf ${PKG_NAME}.tar.gz && cd ${PKG_NAME} && sudo ./install.sh --start"
echo "================================================"