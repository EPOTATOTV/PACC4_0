# =============================================================================
# PACC 底层检测（Linux）构建
#
# 三条产物，依赖完全不同的工具链：
#   pacc_ldm.ko               内核模块（procfs 回退路径，4.15~5.3）—— kbuild 编
#   pacc-ebpf/build/*.bpf.o   eBPF 程序（>=5.4 主路径）—— clang -target bpf 编
#   pacc-ebpf/build/pacc-ebpf-loader  用户态 loader —— 宿主机 cc + libbpf 编
#
# 因此三个工具链**分别探测**：缺哪个就只跳过哪一块，并把「装什么」写清楚，
# 而不是让 make 抛一屏 "No such file or directory: bpf/bpf_helpers.h"。
# 需要硬失败时（比如 CI 明确要 eBPF 产物）用 bpf / loader / ebpf 单独目标。
#
# 常用：
#   make                # 三条产物都尝试构建（缺工具链的部分跳过并告警）
#   make module         # 只编内核模块（需要 /lib/modules/$(uname -r)/build）
#   make ebpf           # 只编 eBPF 对象 + loader（不碰内核模块）
#   make bpf-compat54   # 额外编 5.4 专用变体（该内核缺 bpf_probe_read_user_str）
#   make check          # 语法检查（不需要 vmlinux.h / libbpf）
#   make install        # 装到 $(PREFIX)/lib/pacc/ebpf
# =============================================================================

# =============================================================================
# kbuild 二次进入本 Makefile 时（KERNELRELEASE 非空）只接受内核侧语言：定义 obj-m
# 即可。宿主机构建规则与变量求值全部放在 else 分支，kbuild 不会解析到它们——
# 否则下面的 pattern rule 与 $(shell ...) 会在 kbuild 解析阶段引入意外行为。
# 这是 Documentation/kbuild/modules.rst 的标准两段式写法。
# =============================================================================
ifneq ($(KERNELRELEASE),)
obj-m += pacc_ldm.o

else
# ---- 目录 ----
TOP        := $(dir $(lastword $(MAKEFILE_LIST)))
PWD        := $(shell pwd)
BPF_DIR    := pacc-ebpf
USERSPACE  := userspace
BPF_BUILD  := $(BPF_DIR)/build

# ---- 安装位置（loader 的默认 --object-dir 取自这里，单一来源） ----
PREFIX            ?= /usr/local
PACC_EBPF_LIBDIR  ?= $(PREFIX)/lib/pacc/ebpf
DESTDIR           ?=

# ---- 内核模块（内核源码树位置） ----
KDIR ?= /lib/modules/$(shell uname -r)/build

# ---- 工具链 ----
CLANG   ?= clang
BPFTOOL ?= bpftool
CC      ?= cc
PKGCONF ?= pkg-config

HAS_CLANG   := $(shell command -v $(CLANG) 2>/dev/null)
HAS_BPFTOOL := $(shell command -v $(BPFTOOL) 2>/dev/null)
HAS_PKGCFG  := $(shell command -v $(PKGCONF) 2>/dev/null)
HAS_LIBBPF  := $(shell $(PKGCONF) --exists libbpf 2>/dev/null && echo yes)
# 4.15~5.3 的机器上不会有 libbpf 开发包也不会有 bpftool，这些探测结果只用于
# 决定「跳过还是硬报错」，不影响内核模块的构建。

# ---- 工具链缺失时的统一文案 ----
# 必须在 $(eval $(call PACC_BPF_RULE,...)) 之前定义：eval 会立刻展开宏体里的
# $(PACC_NEED_CLANG)，定义在后就等于没写这段检查。
# 用 command -v 而不是预先算好的 HAS_CLANG，避免变量求值顺序上的坑。
define PACC_NEED_CLANG
@command -v $(CLANG) >/dev/null 2>&1 || { \
	echo "[PACC] 错误：未找到 clang。eBPF 必须用 clang 编（gcc 不支持 -target bpf）。"; \
	echo "         Debian/Ubuntu: apt-get install clang llvm"; \
	echo "         RHEL/Fedora : dnf install clang llvm"; \
	echo "         或指定路径：make bpf CLANG=/path/to/clang"; \
	exit 1; \
}
endef

# ---- 目标架构（libbpf 的 bpf_tracing.h 需要 __TARGET_ARCH_*） ----
HOST_ARCH := $(shell uname -m)
ifeq ($(HOST_ARCH),x86_64)
  TARGET_ARCH := x86
else ifeq ($(HOST_ARCH),aarch64)
  TARGET_ARCH := arm64
else ifeq ($(HOST_ARCH),armv7l)
  TARGET_ARCH := arm
else ifeq ($(HOST_ARCH),ppc64le)
  TARGET_ARCH := powerpc
else ifeq ($(HOST_ARCH),s390x)
  TARGET_ARCH := s390
else ifeq ($(HOST_ARCH),riscv64)
  TARGET_ARCH := riscv
else
  TARGET_ARCH := x86
endif

# ---- eBPF 编译参数 ----
# -Werror=implicit-function-declaration 是刻意加的：设计文档的示例代码里调用了
# 不存在的 bpf_strstr()，那种错误在默认告警下只是「隐式声明」一行提示，很容易被
# 淹没；这里直接当错误拦下来。
BPF_CFLAGS := -target bpf -g -O2 -Wall \
	-Werror=implicit-function-declaration \
	-D__TARGET_ARCH_$(TARGET_ARCH) \
	-I$(BPF_BUILD) -I$(BPF_DIR)

# ---- 用户态 loader 编译参数 ----
LOADER_CFLAGS := -O2 -g -std=gnu11 -Wall -Wextra \
	-Werror=implicit-function-declaration \
	-I$(BPF_DIR) -I$(USERSPACE) \
	-DPACC_DEFAULT_OBJECT_DIR='"$(PACC_EBPF_LIBDIR)"' \
	$(shell $(PKGCONF) --cflags libbpf 2>/dev/null)
LOADER_LIBS := $(shell $(PKGCONF) --libs libbpf 2>/dev/null)

# ---- 源文件与产物 ----
# 六个挂载点各一个 .bpf.c（execve/openat/ptrace/process_vm_writev/load_module/uprobe）
BPF_SRCS  := $(wildcard $(BPF_DIR)/*.bpf.c)
BPF_NAMES := $(patsubst $(BPF_DIR)/%.bpf.c,%,$(BPF_SRCS))

# 三个对象变体，同一个源码编三份：
#   perf          5.4~5.7 的事件通道（PERF_EVENT_ARRAY）
#   ringbuf       5.8+ 的事件通道（RINGBUF）
#   perf-compat54 5.4 专用：该内核没有 bpf_probe_read_user_str（5.5 引入），
#                 退回语义更松的 bpf_probe_read_str。理由见 pacc_common.bpf.h。
BPF_OBJS_perf    := $(addprefix $(BPF_BUILD)/,$(addsuffix .perf.bpf.o,$(BPF_NAMES)))
BPF_OBJS_ringbuf := $(addprefix $(BPF_BUILD)/,$(addsuffix .ringbuf.bpf.o,$(BPF_NAMES)))
BPF_OBJS_compat54 := $(addprefix $(BPF_BUILD)/,$(addsuffix .perf-compat54.bpf.o,$(BPF_NAMES)))
BPF_HDRS := $(BPF_DIR)/pacc_common.bpf.h $(BPF_DIR)/pacc_ebpf.h
USER_HDRS := $(wildcard $(USERSPACE)/*.h)

LOADER := $(BPF_BUILD)/pacc-ebpf-loader
LOADER_SRCS := $(USERSPACE)/pacc_ebpf_loader.c \
	$(USERSPACE)/pacc_capability.c \
	$(USERSPACE)/pacc_procfs_fallback.c

# =============================================================================
# 默认目标：三块都试，缺工具链的跳过
# =============================================================================
all: module ebpf
	@echo ""
	@echo "[PACC] 构建结束。产物："
	@echo "       内核模块   pacc_ldm.ko（procfs 回退路径）"
	@echo "       eBPF 对象  $(BPF_BUILD)/*.bpf.o"
	@echo "       loader     $(LOADER)"
	@echo "       下一步见 README.md 的「构建 / 加载 / 验证」"

module:
	@if [ ! -d "$(KDIR)" ]; then \
		echo "[PACC] 跳过内核模块：$(KDIR) 不存在（未安装内核头文件）。"; \
		echo "       需要 procfs 回退路径时请安装："; \
		echo "         Debian/Ubuntu: apt-get install linux-headers-\$$(uname -r)"; \
		echo "         RHEL/Fedora : dnf install kernel-devel"; \
	else \
		$(MAKE) -C $(KDIR) M=$(PWD) modules; \
	fi

# eBPF 两块：对象 + loader。任一工具链缺失即硬报错（这是显式要求 eBPF 的入口）
ebpf: bpf loader

# =============================================================================
# eBPF 对象
# =============================================================================
bpf: $(BPF_OBJS_perf) $(BPF_OBJS_ringbuf)

bpf-all: $(BPF_OBJS_perf) $(BPF_OBJS_ringbuf) $(BPF_OBJS_compat54)

bpf-compat54: $(BPF_OBJS_compat54)

# 每个变体一条模式规则（用 foreach+eval 生成，避免手抄三遍）
# vmlinux.h 写成普通前置依赖（不是 order-only）：它换了（换了目标内核）就必须重编对象。
define PACC_BPF_RULE
$(BPF_BUILD)/%.$(1).bpf.o: $(BPF_DIR)/%.bpf.c $(BPF_HDRS) $(BPF_BUILD)/vmlinux.h
	$(PACC_NEED_CLANG)
	@mkdir -p $(BPF_BUILD)
	$(CLANG) $(BPF_CFLAGS) $(2) -c $$< -o $$@
	@echo "[PACC] 编出 $$@"
endef
$(eval $(call PACC_BPF_RULE,perf,))
$(eval $(call PACC_BPF_RULE,ringbuf,-DPACC_USE_RINGBUF=1))
$(eval $(call PACC_BPF_RULE,perf-compat54,-DPACC_COMPAT_54=1))

# vmlinux.h：从运行内核的 BTF 生成（CO-RE 的根基）。
# 构建机与目标机内核不同的场合（CI 交叉构建），把与目标内核匹配的 vmlinux.h
# 直接放到 $(BPF_BUILD)/vmlinux.h 即可——make 看到文件已存在就不会重新生成。
$(BPF_BUILD)/vmlinux.h:
	@mkdir -p $(BPF_BUILD)
	@if [ ! -r /sys/kernel/btf/vmlinux ]; then \
		echo "[PACC] 错误：/sys/kernel/btf/vmlinux 不存在。"; \
		echo "       CO-RE 依赖内核 BTF，该内核可能是 CONFIG_DEBUG_INFO_BTF=n 编的。"; \
		echo "       处理办法（三选一）："; \
		echo "         1) 换用带 BTF 的内核（主流发行版 5.4+ 默认开）；"; \
		echo "         2) 手工放一份匹配目标内核的 vmlinux.h 到 $(BPF_BUILD)/vmlinux.h；"; \
		echo "         3) 放弃 eBPF，只用 make module 走 procfs 回退路径。"; \
		exit 1; \
	fi
	@if [ -z "$(HAS_BPFTOOL)" ]; then \
		echo "[PACC] 错误：未找到 bpftool（用它生成 vmlinux.h）。"; \
		echo "         Debian/Ubuntu: apt-get install bpftool"; \
		echo "         RHEL/Fedora : dnf install bpftool"; \
		echo "         或指定路径：make bpf BPFTOOL=/path/to/bpftool"; \
		exit 1; \
	fi
	$(BPFTOOL) btf dump file /sys/kernel/btf/vmlinux format c > $@.tmp
	@mv $@.tmp $@
	@echo "[PACC] 生成 $@（$$(wc -l < $@) 行）"

# =============================================================================
# 用户态 loader
# =============================================================================
loader: $(LOADER)

$(LOADER): $(LOADER_SRCS) $(USER_HDRS)
	@if [ -z "$(HAS_LIBBPF)" ]; then \
		echo "[PACC] 错误：pkg-config 找不到 libbpf。loader 依赖 libbpf（>=1.0 建议）。"; \
		echo "         Debian/Ubuntu: apt-get install libbpf-dev clang llvm"; \
		echo "         RHEL/Fedora : dnf install libbpf-devel clang llvm"; \
		echo "         只想编内核模块/走 procfs 回退： make module"; \
		exit 1; \
	fi
	@mkdir -p $(BPF_BUILD)
	$(CC) $(LOADER_CFLAGS) -o $@ $(LOADER_SRCS) $(LOADER_LIBS)
	@echo "[PACC] 编出 $@"
	@echo "[PACC] 默认对象目录（--object-dir）：$(PACC_EBPF_LIBDIR)"

# =============================================================================
# 语法检查（不需要 vmlinux.h / bpftool / libbpf，用的是一组桩头文件）
# 这是本仓库在 Windows 等无 Linux 工具链的机器上唯一能做的验证。
# =============================================================================
check:
	@if [ -z "$(HAS_CLANG)" ]; then \
		echo "[PACC] 跳过 check：未找到 clang"; \
	else \
		bash $(TOP)tests/syntax_check.sh "$(CLANG)"; \
	fi

# =============================================================================
# 安装
# =============================================================================
install:
	@if [ -e "$(LOADER)" ]; then \
		install -d $(DESTDIR)$(PACC_EBPF_LIBDIR); \
		install -m 0755 $(LOADER) $(DESTDIR)$(PACC_EBPF_LIBDIR)/pacc-ebpf-loader; \
		install -m 0644 $(BPF_OBJS_perf) $(DESTDIR)$(PACC_EBPF_LIBDIR)/; \
		if [ -n "$(wildcard $(BPF_OBJS_ringbuf))" ]; then \
			install -m 0644 $(BPF_OBJS_ringbuf) $(DESTDIR)$(PACC_EBPF_LIBDIR)/; \
		fi; \
		if [ -n "$(wildcard $(BPF_OBJS_compat54))" ]; then \
			install -m 0644 $(BPF_OBJS_compat54) $(DESTDIR)$(PACC_EBPF_LIBDIR)/; \
		fi; \
		echo "[PACC] loader 与 eBPF 对象已装到 $(DESTDIR)$(PACC_EBPF_LIBDIR)"; \
	else \
		echo "[PACC] 跳过 loader 安装：$(LOADER) 不存在（先 make loader）"; \
	fi
	@if [ -f pacc_ldm.ko ]; then \
		$(MAKE) -C $(KDIR) M=$(PWD) modules_install; \
		depmod -a; \
		echo "[PACC] pacc_ldm.ko 已安装（procfs 回退路径用）"; \
	else \
		echo "[PACC] 跳过内核模块安装：pacc_ldm.ko 不存在（先 make module）"; \
	fi

uninstall:
	rm -rf $(DESTDIR)$(PACC_EBPF_LIBDIR)
	@echo "[PACC] 已删除 $(DESTDIR)$(PACC_EBPF_LIBDIR)"
	@echo "[PACC] 内核模块请手工 rmmod pacc_ldm 并从 /lib/modules 删除"

clean:
	@if [ -d "$(KDIR)" ]; then $(MAKE) -C $(KDIR) M=$(PWD) clean; fi
	rm -rf $(BPF_BUILD)
	@echo "[PACC] 已清理（含 $(BPF_BUILD)/，其中 vmlinux.h 也会被删掉）"

.PHONY: all module ebpf bpf bpf-all bpf-compat54 loader check install uninstall clean

endif # KERNELRELEASE