# PACC 内核层检测（Linux / eBPF）

设计文档 §3.1.2 的实现。承担「五层防篡改」里的**内核层校验**，以及进程创建 /
调试注入 / 跨进程内存写 / 内核模块加载这几条行为线索的采集。

对应验收项：**A10**（Linux 5.4+ 可加载）、**A11**（可捕获 execve / ptrace 事件）。
本目录源码在无 Linux 工具链的机器上只能做语法检查，A10/A11 的运行时验证步骤见
文末「验证」一节——请以真机输出为准，本 README 不声称已验证。

---

## 1. 架构

```
用户态 (pacc-ebpf-loader)
    │ ↑ 事件 (NDJSON over UNIX socket / stdout)
    │ ↓ 能力探测 + 挂载 + 轮询
eBPF 程序（六个独立对象，逐个挂载、互不牵连）
    ├── tracepoint/syscalls/sys_enter_execve            进程创建监控
    ├── tracepoint/syscalls/sys_enter_openat            文件打开监控
    ├── tracepoint/syscalls/sys_enter_ptrace            调试/注入检测
    ├── tracepoint/syscalls/sys_enter_process_vm_writev 跨进程内存写
    ├── kprobe/load_module                              内核模块加载
    └── uprobe（dlopen / dlsym）                        用户态钩子监控
```

事件流：`内核事件 → ringbuf/perf → loader 解码 → NDJSON → ptv-client 的
EbpfSource（见 `platform/linux-client/src/ebpf.rs`）`。loader **不直连网络**，
与平台层「仅本地采样」的原则一致。

### 六个挂载点分别抓什么

| SEC | 抓什么 | 事件名 | 默认严重级 |
| --- | --- | --- | --- |
| `tracepoint/syscalls/sys_enter_execve` | 每次进程创建；进程名命中调试/逆向工具表（gdb/lldb/strace/ltrace/frida/cheat-engine/ida/ghidra/radare2/x64dbg/ollydbg）时升级 | `execve` / `execve_suspicious` | low / high |
| `tracepoint/syscalls/sys_enter_openat` | 默认只报敏感路径：`/dev/mem`、`/dev/kmem`、`/proc/kcore`、`/proc/self/mem`、`/proc/self/pagemap`、`/proc/<pid>/mem`、`/sys/kernel/debug`、`/sys/kernel/tracing`、`/memfd:` | `openat` / `openat_sensitive` | low / medium |
| `tracepoint/syscalls/sys_enter_ptrace` | 全部 ptrace 调用；`PTRACE_ATTACH(16)`/`POKETEXT(4)`/`POKEDATA(5)` 升级为危险 | `ptrace` / `ptrace_dangerous` | low / high |
| `tracepoint/syscalls/sys_enter_process_vm_writev` | 全部跨进程内存写（不需要调试权限即可改写他人地址空间） | `process_vm_writev` | high |
| `kprobe/load_module` | 内核模块加载（反 rootkit），记录 com/pid/ppid/uid 与模块参数、`IGNORE_MODVERSIONS/VERMAGIC` 绕过位 | `load_module` | medium |
| `uprobe` on libc `dlopen` / `dlsym` | **按 PID 挂载**到受保护进程，抓「有外来共享库/符号被装进游戏进程」 | `uprobe_hook` | medium |

`openat` 默认不全量（每秒上万条会把环缓冲淹掉，且正常玩家与作弊工具在这些事件上
没有区别）。需要全量审计时把 `pacc_config.capture_all_openat` 置 1——ARRAY map，
加载后可改，不用重新加载程序：`--capture-all-openat`。

### 事件通道：ringbuf 与 perf 都实现，编译期二选一

`BPF_MAP_TYPE_RINGBUF` 需要 5.8+，而兼容性下限是 5.4，且 libbpf 在 load 程序之前
要建好对象里的**所有** map，一个 map 建不出来整个对象就加载失败——所以两个 map
不能同时出现在一个对象里。因此 Makefile 用同一份 `.bpf.c` 编三个变体：

| 变体 | 编译宏 | 事件通道 | 适用内核 |
| --- | --- | --- | --- |
| `perf` | — | `BPF_MAP_TYPE_PERF_EVENT_ARRAY` | 5.4 ~ 5.7（默认，也是兜底） |
| `ringbuf` | `-DPACC_USE_RINGBUF=1` | `BPF_MAP_TYPE_RINGBUF` | 5.8+ |
| `perf-compat54` | `-DPACC_COMPAT_54=1` | perf + `bpf_probe_read_str` | 恰好 5.4 |

**为什么单给 5.4 一个变体**：5.4 有 CO-RE，但读用户态指针的严格版 helper
`bpf_probe_read_user_str` 是 **5.5** 才引入的（helper id 114）。5.4 上调它会得到
"unknown func"。所以 5.4 要么用 compat 变体（退回语义更松的 `bpf_probe_read_str`，
理论上存在把内核地址当用户地址读到的可能），要么走 procfs 回退。loader 会**自动
优先尝试 compat54 变体**，不需要人工判断。

优先 ringbuf 的理由：无 per-CPU 副本、没有「丢失计数漂移」、事件天然有序。
保留 perf 路径不是历史包袱，而是 5.4~5.7 上唯一可用的通道。

---

## 2. 目录结构

```
kernel-linux/
  Makefile                 三条产物（ko / .bpf.o / loader）+ 工具链分别探测
  pacc_ldm.c               内核模块：procfs 回退路径（4.15~5.3）+ kprobe 命中统计
  pacc-ebpf/
    pacc_ebpf.h            事件契约（内核与用户态**共用同一份**）
    pacc_common.bpf.h      vmlinux.h/libbpf 头顺序、事件 map、配置 map、比较助手
    execve.bpf.c           ↑ 六个挂载点，一个文件一个关注点
    openat.bpf.c
    ptrace.bpf.c
    process_vm_writev.bpf.c
    load_module.bpf.c
    uprobe.bpf.c
  userspace/
    pacc_ebpf_loader.c     main：能力门控 → 变体选择 → 加载/挂载 → 轮询 → NDJSON
    pacc_capability.{c,h}  能力探测与「选哪条路」的理由字符串
    pacc_procfs_fallback.{c,h}  procfs 回退扫描（进程名/命令行特征 + TracerPid）
    pacc_loader.h          事件出口与事件编码的对外声明
    pacc_json.h            NDJSON 拼装（两处生产者共用，避免转义逻辑漂移）
  tests/
    syntax_check.sh        语法检查（无需 vmlinux.h/bpftool/libbpf）
    makefile_lint.py       Makefile 结构检查（无 make 的机器上代替 make -n）
    stubs/                 桩头文件，**仅供 syntax_check.sh**，切勿进真实构建
```

---

## 3. 内核版本兼容矩阵

| 内核版本 | CO-RE | ringbuf | `*_user_str` | PACC 采集路径 |
| --- | --- | --- | --- | --- |
| **>= 5.8** | ✅ | ✅ | ✅ | **eBPF + ringbuf 变体**（首选） |
| **5.5 ~ 5.7** | ✅ | ❌ | ✅ | **eBPF + perf 变体** |
| **5.4** | ✅ | ❌ | ❌ | 先试 `perf-compat54` 变体；失败则 procfs |
| **4.15 ~ 5.3** | ❌ | ❌ | — | **procfs 回退**：用户态 `/proc` 轮询 + `pacc_ldm.ko` 的内核侧命中统计 |
| < 4.15 | ❌ | ❌ | — | 只做用户态 `/proc` 轮询（能力弱，仅进程现状快照） |

回退路径的能力差异**是能力差异，不是实现差异**：procfs 轮询只能给「现在有什么进程
在跑」的快照，拿不到「谁 execve 了、谁 ptrace 了」这类事件。loader 会在启动时把选择
结果和理由同时写进 stderr 与一条 `probe_status` 事件（含 `reason` 字段），
所以「这台机器为什么没用上 eBPF」永远有一句话可查，不是靠猜。

判定策略是**先实测再看版本**：版本够不等于能用（内核可能 `CONFIG_BPF_SYSCALL=n`、
`CONFIG_DEBUG_INFO_BTF=n`，容器里可能被 seccomp 拦）。`pacc_probe_capability()`
真的去调一次 `bpf(BPF_MAP_CREATE)` 建最小 map，并按 errno 区分
「没权限（EPERM/EACCES）」与「内核不支持（ENOSYS/EINVAL）」。

---

## 4. 构建

### 前置依赖

| 用途 | Debian/Ubuntu | RHEL/Fedora |
| --- | --- | --- |
| 编 eBPF | `clang llvm`（需含 BPF 后端） | `clang llvm` |
| 生成 vmlinux.h | `bpftool` | `bpftool` |
| 编 loader | `libbpf-dev pkg-config` | `libbpf-devel pkg-config` |
| 编内核模块 | `linux-headers-$(uname -r)` | `kernel-devel` |

```bash
sudo apt-get install -y clang llvm bpftool libbpf-dev pkg-config \
                        linux-headers-$(uname -r)
```

### 命令

```bash
cd platform/kernel-linux

make check            # 语法检查：不需要 vmlinux.h / bpftool / libbpf / 内核头文件
make bpf              # 生成 pacc-ebpf/build/vmlinux.h + 12 个 .bpf.o（perf/ringbuf 两变体）
make bpf-compat54     # 可选：额外编 5.4 专用变体（6 个对象）
make loader           # 编译 userspace loader
make module           # 编译 pacc_ldm.ko（procfs 回退路径）
make                  # 以上全部（缺哪块工具链就跳过哪块并说明装什么）

sudo make install     # 装到 /usr/local/lib/pacc/ebpf（loader + 对象文件）
```

`vmlinux.h` 由 `bpftool btf dump file /sys/kernel/btf/vmlinux format c` 生成。
交叉构建（构建机内核 ≠ 目标机内核）时，把匹配目标内核的 `vmlinux.h` 直接放到
`pacc-ebpf/build/vmlinux.h`，make 看到文件已存在就不会重新生成。

工具链缺失时给的是**可执行的下一步**而不是一屏报错，例如：

```
[PACC] 错误：pkg-config 找不到 libbpf。loader 依赖 libbpf（>=1.0 建议）。
         Debian/Ubuntu: apt-get install libbpf-dev clang llvm
         只想编内核模块/走 procfs 回退： make module
```

---

## 5. 运行

```bash
cd platform/kernel-linux

# 事件写 UNIX socket（linux-client 的默认订阅路径）
sudo ./pacc-ebpf/build/pacc-ebpf-loader \
     --object-dir pacc-ebpf/build \
     --socket /run/pacc/pacc-ldm.sock \
     --pid "$(pgrep -f 'java.*minecraft' | head -1)"

# 事件写 stdout（排障用；-v 会打出 libbpf 调试日志）
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout -v
```

主要参数：

| 参数 | 说明 |
| --- | --- |
| `--object-dir DIR` | `.bpf.o` 目录，默认编译期值 `/usr/local/lib/pacc/ebpf` |
| `--socket PATH` / `--stdout` | 事件出口，默认 `/run/pacc/pacc-ldm.sock` |
| `--pid PID` | 受保护进程，**uprobe 的挂载目标**，可重复 |
| `--uprobe-lib PATH` | uprobe 目标二进制，默认 `libc.so.6` |
| `--uprobe-sym-dlopen/-dlsym SYM` | 覆盖默认符号名 |
| `--capture-all-openat` | openat 全量上报（默认只报敏感路径） |
| `--no-capture-execve` | 关闭 execve 全量上报 |
| `--force-procfs` | 强制走回退路径（验证回退逻辑用） |
| `--variant NAME` | 强制对象变体：`ringbuf` / `perf` / `perf-compat54` |
| `--interval SEC` | procfs 回退的扫描间隔，默认 15 |

`SIGTERM/SIGINT` 干净退出；`SIGHUP` 只打印提示——**uprobe 是 per-PID 挂载**，
受保护进程集合变化后需要重启 loader 才会重新挂载（不是 bug，是刻意保持简单：
自动重挂要在信号处理里做 map/link 生命周期管理，风险大于收益）。

### 需要的权限

| 内核 | 需要的能力 |
| --- | --- |
| >= 5.8 | root 或 `CAP_BPF`（+ `CAP_PERFMON`，若 perf_event 被加固） |
| 5.4 ~ 5.7 | root 或 `CAP_SYS_ADMIN`（`CAP_BPF` 是 5.8 才拆出来的） |
| kprobe / uprobe | 另需 `CAP_SYS_ADMIN`（5.8+ 上也是） |
| 内核模块 `pacc_ldm.ko` | root（`insmod`） |
| procfs 回退读取 `/proc` | 非特权也能跑（受 `hidepid`、`kernel.yama.ptrace_scope` 影响） |

无权限时 loader **不会静默降级**：`bpf()` 返回 EPERM 会被识别并写成
「需要 root 或 CAP_BPF（内核 <5.8 需 CAP_SYS_ADMIN）」，然后才切 procfs。

---

## 6. 验证

### 6.1 无需 Linux 工具链的部分（本仓库开发机上已跑过）

```bash
cd platform/kernel-linux
make check                                   # 语法检查（桩头文件）
bash tests/syntax_check.sh clang --codegen   # 真编对象（需 clang 含 BPF 后端）
python3 tests/makefile_lint.py Makefile      # Makefile 结构检查（代替 make -n）
```

`make check` 用 `tests/stubs/` 的桩头文件替代 `vmlinux.h` 与 libbpf 头，能查出
宏/类型/签名/括号/注释闭合这类问题（本次就靠它抓到一处 `/*/` 提前闭合注释的
真实编译错误）。它**不能**验证 CO-RE 重定位、map 定义、校验器行为、能否 attach。
`makefile_lint.py` 检查条件块配对、`$()` 配平、recipe 缩进（missing separator）、
以及 `PACC_BPF_RULE` 三变体展开是否完整——它同样**不等于** make 能正确执行。

### 6.2 A10 / A11 的真机验证（在 Linux 5.4+ 上以 root 执行）

```bash
# ---- A10：eBPF 程序能不能加载 ----
cd platform/kernel-linux && make bpf loader
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout -v
# 期望：每个挂载点一行「挂载点「xxx」就绪」，且 stdout 首行是一条 probe_status
#       事件，mode=ebpf-ringbuf 或 ebpf-perf。
# 若某挂载点失败：日志里会有该挂载点的具体原因，其余挂载点仍应「就绪」。

# 复核内核侧视角
sudo bpftool prog list | grep -c pacc_        # 期望同已就绪挂载点数
sudo bpftool map list | grep pacc_            # pacc_events / pacc_perf_events / pacc_config / pacc_scratch

# ---- A11：能不能捕获 execve 与 ptrace 事件 ----
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout   # 终端 A
# 终端 B：
gdb --version                                  # 触发 execve（gdb 在可疑名表里）
sudo strace -p 1 >/dev/null 2>&1 & sleep 1; kill %1   # 触发 ptrace（ATTACH）
# 终端 A 期望看到：
#   {"event":"execve_suspicious",...,"comm":"gdb",...,"path":"/usr/bin/gdb",...}
#   {"event":"ptrace_dangerous",...,"detail":{"arg0":16,...}}    # 16 = PTRACE_ATTACH
# 按 Ctrl-C 收尾，stderr 会打出累计计数：
#   事件统计：total=.. execve=.. execve_susp=.. ptrace=.. ptrace_dang=..
# 这一行是 A11 最直接的证据。

# ---- 跨进程内存写 ----
python3 -c 'import ctypes,os; ...' 或者用现成工具触发 process_vm_writev 后，
# 终端 A 期望 {"event":"process_vm_writev","severity":"high",...}

# ---- 内核模块加载 ----
sudo insmod /tmp/hello.ko   # 任一模块
# 终端 A 期望 {"event":"load_module",...}
# 注：模块名无法在内核侧可靠取得（理由见 pacc-ebpf/load_module.bpf.c），
#     需要模块名请比较事件前后的 /proc/modules 差异；事件的 purpose 是时间线。

# ---- uprobe（需要 --pid）----
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout \
     --pid "$(pgrep -n bash)"
# 在另一个终端里让该进程 dlopen 一个库（例如 python3 -c 'import ctypes' 走同一 PID 更简单）
# 期望 {"event":"uprobe_hook","path":"libxxx.so",...}

# ---- 回退路径 ----
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout --force-procfs
# 期望日志「采集路径选择：procfs 回退（被 --force-procfs 强制指定）」，
# 且 stdout 出现 {"event":"procfs_scan","source":"procfs-fallback",...} / {"event":"debugger_attached"...}

# ---- 内核模块（procfs 回退路径的另一半）----
make module && sudo insmod pacc_ldm.ko
ls /sys/kernel/debug/pacc/          # add_protected_pid / clear_protected_pids / stats
echo $$ | sudo tee /sys/kernel/debug/pacc/add_protected_pid
sudo cat /sys/kernel/debug/pacc/stats   # ptrace=.. pvmread=.. devmem_write=..
```

### 6.3 从 5.4 或更老内核上退化

```bash
# 4.15~5.3：期望自动落到 procfs，且 reason 里写清版本
uname -r                                     # 例如 4.19.0-xx
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout
# 期望：「内核 4.19.0 低于 eBPF CO-RE 下限 5.4（4.15~5.3 走 procfs 回退…）」
# 未加载 pacc_ldm.ko 时还会提示「如需内核侧的 ptrace 命中统计，请 insmod pacc_ldm.ko」

# 恰好 5.4：期望优先尝试 perf-compat54
make bpf-compat54
sudo ./pacc-ebpf/build/pacc-ebpf-loader --object-dir pacc-ebpf/build --stdout
# 期望：「尝试对象变体：perf-compat54」并在成功后进入 ebpf-perf 模式
```

---

## 7. 未验证事项（如实声明）

下面这些**没有**在本仓库验证过，因为开发机是 Windows，没有 Linux 内核、没有
libbpf、没有 `/sys/kernel/btf/vmlinux`，且本机 clang 21.1.6 未编入 BPF 后端
（`-target bpf -fsyntax-only` 可用，`-c` 编代码生成报 "unable to create target"）：

1. **CO-RE 字段重定位**能否在目标内核 BTF 上解出（`real_parent->pid` 等）；
2. **BTF 定义的 map** 是否被 libbpf 接受（`pacc_ringbuf`/`pacc_perf_events`/
   `pacc_config`/`pacc_scratch` 的形态）；
3. **BPF 校验器是否放行**——特别是 `pacc_basename_off()` / `pacc_ends_with_mem()`
   里「展开循环 + 显式夹紧后按运行期偏移访问栈缓冲」这套写法。这是本次实现里
   校验器风险最高的地方，写法本身是标准手法（先夹紧再由展开的常量下标访问），
   但**没有经过真实校验器**；
4. 六个挂载点能否真的 attach 上（尤其 `kprobe/load_module`——该函数是 static，
   内核 6.x 的模块加载器重写过；loader 已实现候选符号回退链）；
5. loader 能否与 gcc + libbpf 真实链接（本机只做了语法检查，无 libbpf 可链）；
6. 事件结构在真机上的字节级对齐（编译器无关，但没实测过 `sizeof == 336`）。
   `pacc_ebpf.h` 里有编译期静态断言，真机编译时会自动兜住这一项。

验收 **A10 / A11 的判定必须以上面 6.2 的真机输出为准**。

---

## 8. 与设计文档示例代码的差异（以及为什么）

| 文档示例 | 本实现 | 原因 |
| --- | --- | --- |
| `bpf_strstr(...)` | 自实现的定长前缀匹配（`pacc_prefix_eq16/32`，展开循环） | **内核里没有 `bpf_strstr` 这个 helper**。照抄会编译失败，侥幸编译过也会在加载时报 "unknown func"。 |
| `static const char *SUSPICIOUS_NAMES[]` | `static const char [11][16]`（二维字符数组） | 指针数组要写重定位，校验器拿到的是「指向 .rodata 的指针」，无法按文档那样使用。二维数组是连续 .rodata、无指针，展开后下标是编译期常量。 |
| `BPF_MAP_TYPE_PERF_EVENT_ARRAY` | ringbuf 与 perf **都实现**，编译期二选一 | 文档两个都提到；ringbuf 需 5.8+ 而兼容性下限 5.4，不能只留一个。 |
| `bpf_probe_read_kernel_str` 读 syscall 参数 | `bpf_probe_read_user_str` | tracepoint 的 `args[]` 全是**用户态指针**；用 kernel 版会返回 -EFAULT，静默没数据。 |
| 未提栈限制 | 事件体放 per-CPU map（`pacc_scratch`） | `struct pacc_event` 336 字节 + 路径 256 字节 > BPF 的 512 字节栈上限，直接写栈会加载失败。 |
| 未提模块名 | 内核侧不解模块名 | 读模块名要走文件私有结构 `load_info` 或 `struct module::name`（6.4 前后形态变过），CO-RE 定位失败会让**整个对象**加载失败——把反 rootkit 这条线做成最脆弱的一环不划算。事件里带 `detail.note` 说明去哪儿补。 |
| 未提 ptrace `SEIZE` | 不列为危险 | `PTRACE_SEIZE(0x4206)` 是「不停止目标」的 attach，现代调试器与监控 agent 都用；它可疑但不足以在内核层定性，交给用户态规则层按进程信誉判定。 |

---

## 9. 已知限制

- `openat2(2)` 未单独挂载点（作弊工具基本走经典 `openat`；等现网真抓到绕过再加）。
- `process_vm_writev` 只读**第一个**目标 iovec（地址+长度），不做变长数组遍历。
- uprobe 需要显式 `--pid`；不指定时该挂载点会明确报「跳过」而不是假装在监控。
- `process_vm_readv` 未挂载（设计文档只点名 writev）；内核模块 `pacc_ldm.c` 仍会
  统计对受保护进程的 `process_vm_readv`，两条路互补。
- 版本号：`pacc_ldm.c` 的 `MODULE_VERSION` 由 `scripts/bump-version.sh` 统一改，
  本目录新增的 eBPF/loader 源码**不含**版本字面量（loader 打 `abi=1`），
  避免与脚本维护的版本号漂移。