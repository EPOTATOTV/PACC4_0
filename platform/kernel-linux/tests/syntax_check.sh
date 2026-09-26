#!/usr/bin/env bash
# ============================================================================
# PACC platform/kernel-linux 语法检查
#
# 目的：在没有 vmlinux.h / bpftool / libbpf / linux-headers 的机器上（例如本仓库
#       的开发机），仍然对 BPF 程序与用户态 loader 做一次真正有意义的编译检查。
# 手段：用 tests/stubs/ 下的**桩头文件**替代 vmlinux.h 与 libbpf 头。
#
# 这**不是**完整验证。桩文件只覆盖源码用到的声明，因此下面这些只能到
# Linux 5.4+ 真机上验证（见 README 的「验证」一节）：
#   * CO-RE 字段重定位能否在目标内核 BTF 上解出；
#   * BTF 定义的 map 能否被 libbpf 接受；
#   * BPF 校验器是否放行（循环展开、栈访问边界、helper 可用性）；
#   * 六条挂载点能否真的 attach 上并产出事件（验收 A10 / A11）。
#
# 用法：
#   bash tests/syntax_check.sh [clang]              # 仅语法检查（快）
#   bash tests/syntax_check.sh [clang] --codegen    # 真编到对象文件（-c -O2），
#                                                   # 能多查出展开/代码生成阶段的问题
#                                                   # 产物写到临时目录，**不落到仓库里**
# ============================================================================
set -u

CLANG="${1:-clang}"
MODE="${2:-syntax}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
STUBS="$HERE/stubs"
LOG="${TMPDIR:-/tmp}/pacc_syntax_check.log"
OUTDIR=""
FAILED=0
CHECKED=0
SKIPPED=0

if ! command -v "$CLANG" >/dev/null 2>&1; then
	echo "[check] 未找到 $CLANG，跳过" >&2
	exit 2
fi

if [ "$MODE" = "--codegen" ]; then
	OUTDIR="$(mktemp -d "${TMPDIR:-/tmp}/pacc-codegen.XXXXXX")"
	# 产物一律写临时目录并在退出时删除：仓库里不留 .o（CI 会自己编）
	trap 'rm -rf "$OUTDIR"' EXIT INT TERM
fi

echo "[check] clang: $($CLANG --version | head -1)"
echo "[check] 桩头文件: $STUBS"
echo "[check] 模式: $MODE"
echo "[check] 提示：桩检查只覆盖语法/类型/宏展开；CO-RE 与校验器行为需真机验证"

# ---------- 1. eBPF 程序（三个变体都要过） ----------
for src in "$ROOT"/pacc-ebpf/*.bpf.c; do
	[ -e "$src" ] || continue
	for variant in "perf:" "ringbuf:-DPACC_USE_RINGBUF=1" "compat54:-DPACC_COMPAT_54=1"; do
		vname="${variant%%:*}"
		vflags="${variant#*:}"
		CHECKED=$((CHECKED + 1))
		# shellcheck disable=SC2086  # $vflags/$GENFLAGS 故意不加引号：需按空格拆参数
		if [ "$MODE" = "--codegen" ]; then
			out="$OUTDIR/$(basename "$src" .bpf.c).$vname.bpf.o"
			if "$CLANG" -target bpf -g -O2 -Wall \
					-Werror=implicit-function-declaration \
					-D__TARGET_ARCH_x86 \
					-I"$STUBS" -I"$ROOT/pacc-ebpf" -I"$ROOT/userspace" \
					$vflags -c "$src" -o "$out" >"$LOG" 2>&1; then
				echo "[check] OK    $(basename "$src") [$vname] $(wc -c <"$out") 字节"
			elif grep -q "unable to create target" "$LOG" 2>/dev/null; then
				# 该 clang 没编进 BPF 后端（本仓库开发机的 Swift clang 就是这种）。
				# 代码生成阶段跑不了，但这不代表源码有问题——如实报 SKIP 而不是 FAIL，
				# 否则 CI 会因为这个环境差异一直红。
				echo "[check] SKIP  $(basename "$src") [$vname] 本机 clang 无 BPF 后端"
				SKIPPED=$((SKIPPED + 1))
			else
				echo "[check] FAIL  $(basename "$src") [$vname]"
				sed -n '1,40p' "$LOG"
				FAILED=$((FAILED + 1))
			fi
		elif "$CLANG" -target bpf -fsyntax-only -Wall \
				-Werror=implicit-function-declaration \
				-D__TARGET_ARCH_x86 \
				-I"$STUBS" -I"$ROOT/pacc-ebpf" -I"$ROOT/userspace" \
				$vflags "$src" >"$LOG" 2>&1; then
			echo "[check] OK    $(basename "$src") [$vname]"
		else
			echo "[check] FAIL  $(basename "$src") [$vname]"
			sed -n '1,40p' "$LOG"
			FAILED=$((FAILED + 1))
		fi
	done
done

# ---------- 2. 用户态 loader ----------
# -Wno-deprecated-declarations / _CRT_*_NO_WARNINGS：宿主机若是 Windows（MSVC 的
# CRT 会把 fopen/unlink/strerror 标成 deprecated），会产生一堆与 Linux 无关的噪声；
# 在真机上用系统头文件时这些宏无副作用。
for src in "$ROOT"/userspace/*.c; do
	[ -e "$src" ] || continue
	CHECKED=$((CHECKED + 1))
	if "$CLANG" -fsyntax-only -std=gnu11 -Wall -Wextra \
			-Werror=implicit-function-declaration \
			-Wno-deprecated-declarations \
			-D_CRT_SECURE_NO_WARNINGS -D_CRT_NONSTDC_NO_WARNINGS \
			-DPACC_DEFAULT_OBJECT_DIR='"/usr/local/lib/pacc/ebpf"' \
			-I"$STUBS" -I"$ROOT/userspace" -I"$ROOT/pacc-ebpf" \
			"$src" >"$LOG" 2>&1; then
		echo "[check] OK    $(basename "$src")"
	else
		echo "[check] FAIL  $(basename "$src")"
		sed -n '1,40p' "$LOG"
		FAILED=$((FAILED + 1))
	fi
done

echo "[check] 共检查 $CHECKED 项，失败 $FAILED 项，跳过 $SKIPPED 项"
[ "$FAILED" -eq 0 ] || exit 1
if [ "$SKIPPED" -ne 0 ]; then
	echo "[check] 语法检查通过；$SKIPPED 项因本机 clang 无 BPF 后端未做代码生成检查"
	echo "[check] 代码生成与加载验证必须在装 clang/llvm(含 BPF 后端)+libbpf 的 Linux 上做"
else
	echo "[check] 语法检查通过（注意：这不等于能在内核里加载运行）"
fi
exit 0