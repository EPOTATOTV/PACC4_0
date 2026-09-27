#!/usr/bin/env bash
# PACC Linux 原生客户端安装脚本
#
# 用法：
#   sudo ./install.sh                      # 安装（不启动）
#   sudo ./install.sh --start              # 安装并立即启动
#   sudo ./install.sh --binary /path/to/pacc-linux-client
#   sudo ./install.sh --prefix /usr/local  # 前缀目录，默认 /usr/local
#   sudo ./install.sh --no-enable          # 只装文件，不 enable
#
# 幂等：重复执行不会重复建账号、不会覆盖已有的 /etc/pacc/pacc-client.properties。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BIN_NAME="pacc-linux-client"
PREFIX="/usr/local"
BIN_DIR=""
CONF_DIR="/etc/pacc"
CONF_FILE="${CONF_DIR}/pacc-client.properties"
UNIT_DIR="/etc/systemd/system"
SERVICE_NAME="pacc-client.service"
SERVICE_USER="pacc"
DEFAULT_BIN="${SCRIPT_DIR}/target/release/${BIN_NAME}"

SRC_BIN=""
DO_START="no"
DO_ENABLE="yes"

while [ $# -gt 0 ]; do
  case "$1" in
    --binary) SRC_BIN="${2:-}"; shift 2 ;;
    --prefix) PREFIX="${2:-}"; shift 2 ;;
    --start) DO_START="yes"; shift ;;
    --no-enable) DO_ENABLE="no"; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

BIN_DIR="${PREFIX}/bin"

step() { printf '\n\033[1m[%s]\033[0m\n' "$1"; }
warn() { printf '  [注意] %s\n' "$1"; }
ok() { printf '  [完成] %s\n' "$1"; }

# ------------------------------------------------------------------ 前置检查

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root 权限（会写 /usr/local、/etc 并创建 systemd 用户）。请用 sudo 执行。" >&2
  exit 1
fi

if [ -z "$SRC_BIN" ]; then
  SRC_BIN="$DEFAULT_BIN"
fi
if [ ! -f "$SRC_BIN" ]; then
  echo "找不到二进制：${SRC_BIN}" >&2
  echo "请先构建：cargo build --release（或解压发行包后用包内 bin/ 目录）" >&2
  exit 1
fi
if [ ! -f "${SCRIPT_DIR}/${SERVICE_NAME}" ]; then
  echo "找不到 ${SCRIPT_DIR}/${SERVICE_NAME}（install.sh 需与单元文件同目录）" >&2
  exit 1
fi

HAS_SYSTEMD="no"
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
  HAS_SYSTEMD="yes"
fi

echo "================================================"
echo " PACC Linux 客户端安装"
echo " 二进制   : ${SRC_BIN}"
echo " 安装到   : ${BIN_DIR}/${BIN_NAME}"
echo " 配置     : ${CONF_FILE}"
echo " systemd  : ${HAS_SYSTEMD}"
echo "================================================"

# ------------------------------------------------------------------ 1. 账号
step 1/6 "创建服务账号"

if id -u "$SERVICE_USER" >/dev/null 2>&1; then
  ok "systemd 用户 ${SERVICE_USER} 已存在，跳过"
else
  # --system：不建家目录、UID 落在系统区间、密码锁定。
  useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
  ok "已创建系统用户 ${SERVICE_USER}"
fi

# ------------------------------------------------------------------ 2. 二进制
step 2/6 "安装二进制"

install -d -m 0755 "$BIN_DIR"
install -m 0755 "$SRC_BIN" "${BIN_DIR}/${BIN_NAME}"
ok "${BIN_DIR}/${BIN_NAME}"
"${BIN_DIR}/${BIN_NAME}" --version || warn "二进制无法执行，请确认架构（uname -m）与内核版本匹配"

# ------------------------------------------------------------------ 3. 配置
step 3/6 "准备配置目录"

install -d -m 0750 -o root -g "$SERVICE_USER" "$CONF_DIR"
if [ -f "$CONF_FILE" ]; then
  ok "${CONF_FILE} 已存在，保留原内容"
else
  if [ -f "${SCRIPT_DIR}/pacc-client.properties.example" ]; then
    install -m 0640 -o root -g "$SERVICE_USER" \
      "${SCRIPT_DIR}/pacc-client.properties.example" "$CONF_FILE"
  else
    umask 027
    cat >"$CONF_FILE" <<'EOF'
# PACC Linux 客户端配置（由 install.sh 生成的最小模板）
# 完整的键说明见发行包内 pacc-client.properties.example 或 README。
pacc.client.server.uri=http://127.0.0.1:8080
pacc.client.events.path=/api/player/security/events
pacc.client.pteid=PT0000000001
pacc.client.edition=LINUX
pacc.client.heartbeat.seconds=15
# 令牌与 PTEID 必须按实际玩家填写，否则服务端会 401 拒收。
pacc.client.token=
EOF
    chown root:"$SERVICE_USER" "$CONF_FILE"
    chmod 0640 "$CONF_FILE"
  fi
  ok "已生成 ${CONF_FILE}"
  warn "请检查 pteid / token / server.uri 后再启动（默认值是占位符）"
fi

# ------------------------------------------------------------------ 4. 模型（可选）
step 4/6 "端侧 AI 模型（可选）"

MODEL_DST="${CONF_DIR}/model.bin"
if [ -f "${SCRIPT_DIR}/model.bin" ]; then
  install -m 0640 -o root -g "$SERVICE_USER" "${SCRIPT_DIR}/model.bin" "$MODEL_DST"
  ok "已安装 ${MODEL_DST}（记得在配置里设 pacc.client.model.path）"
else
  printf '  [跳过] 包内无 model.bin，端侧 AI 走回退（回退分不参与判定）\n'
fi

# ------------------------------------------------------------------ 5. systemd
step 5/6 "安装 systemd 单元"

install -m 0644 "${SCRIPT_DIR}/${SERVICE_NAME}" "${UNIT_DIR}/${SERVICE_NAME}"
ok "${UNIT_DIR}/${SERVICE_NAME}"

if [ "$HAS_SYSTEMD" = "yes" ]; then
  systemctl daemon-reload
  ok "systemctl daemon-reload"

  if [ "$DO_ENABLE" = "yes" ]; then
    systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true
    ok "已设为开机自启"
  else
    printf '  [跳过] 按 --no-enable 未设置开机自启\n'
  fi

  if [ "$DO_START" = "yes" ]; then
    systemctl restart "$SERVICE_NAME"
    ok "已启动，查看日志：journalctl -u ${SERVICE_NAME} -f"
  else
    printf '  [提示] 启动：systemctl start %s\n' "$SERVICE_NAME"
  fi
else
  warn "当前环境没有运行中的 systemd，已只安装单元文件"
  warn "手工前台运行：${BIN_DIR}/${BIN_NAME} --config ${CONF_FILE} --once"
fi

# ------------------------------------------------------------------ 6. 收尾
step 6/6 "内核侧（可选）"

printf '  [提示] eBPF 事件源需要 platform/kernel-linux 的 loader 提供 %s\n' "/run/pacc/pacc-ldm.sock"
printf '  [提示] 该 socket 需对 %s 用户可读写（chgrp %s && chmod 0660）\n' "$SERVICE_USER" "$SERVICE_USER"
printf '  [提示] loader 未就绪也不影响运行：客户端会自动回退到 procfs 扫描\n'

echo
echo "================================================"
echo " 安装完成。常用命令："
echo "   验证配置  : ${BIN_DIR}/${BIN_NAME} --config ${CONF_FILE} --once"
echo "   启动/停止 : systemctl start|stop ${SERVICE_NAME}"
echo "   看日志    : journalctl -u ${SERVICE_NAME} -f"
echo "   看事件源  : journalctl -u ${SERVICE_NAME} | grep 事件源选择"
echo "================================================"