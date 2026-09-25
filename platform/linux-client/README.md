# PACC Linux 原生客户端

Linux 玩家机上的端侧检测进程。三轮：选事件源（eBPF 优先，procfs 回退）→ 采集并判定 → 把事件 HTTP POST 给 PACC 后端。

上游是 `../rust-core`。这里是跨平台检测核心的 7 个 crate，客户端只经 `pacc-core` 这个门面用它，不碰下面各层内部。内核侧事件来自 `../kernel-linux`（LKM，独立目录，本目录不含也不构建它）。

和 Java 端 `ptv-client` 的关系：配置口径、上报端点、上报体形态都对齐，运维文档可以共用一份。差别在数据来源——`ptv-client` 挂在游戏 JVM 里，能拿到游戏内事件；本客户端在进程外，只有系统层面的只读取证。

## 架构

```
                    ┌──────────────── platform/linux-client ────────────────┐
kernel-linux (LKM)  │                                                       │
  pacc_ldm.ko       │   ebpf.rs                                             │
  └─ UNIX socket ───┼──►  NDJSON 解析 ─┐                                   │
     (NDJSON)       │                   │                                   │
                    │                   ├──► main.rs 主循环                 │
  /proc, /dev/input │   procfs.rs       │      │                            │
  └─────────────────┼──► 回退扫描 ──────┘      │  DetectionEvent            │
                    │                          ▼                            │
                    │                    pacc-core (facade)                 │
                    │                    ├─ pacc-collector  178 维特征折算  │
                    │                    ├─ pacc-behavior   时序/分布模型   │
                    │                    ├─ pacc-engine     规则 + 分值融合 │
                    │                    ├─ pacc-ml         端侧 AI 推理     │
                    │                    ├─ pacc-stealth    隐身/反检测检查  │
                    │                    └─ pacc-platform   平台抽象          │
                    │                          │                            │
                    │   reporter.rs            │  上报体 JSON               │
                    │   └─ HTTP/1.1 POST ◄─────┘                            │
                    └───────────────────────────────────────────────────────┘
                                    │  Authorization: Bearer <token>
                                    ▼
                          PACC 后端 /api/player/security/events
```

主循环每轮做四件事：取源侧事件 → `snapshot()` 出一份检测结论 → 判断要不要上报 → 睡到下一个周期。睡眠切成 200ms 片段，为的是收到 SIGTERM 能及时收敛。

## 目录内容

```
linux-client/
  Cargo.toml                      独立 workspace 根（path 依赖 ../rust-core/pacc-core）
  src/
    main.rs                        入口：CLI、事件源选择、主循环、信号、上报体组装
    config.rs                      properties + PACC_CLIENT_* 环境变量
    ebpf.rs                        UNIX socket 事件源（NDJSON）、指数退避重连
    procfs.rs                      纯 Rust /proc 扫描回退
    reporter.rs                    零依赖 HTTP/1.1 POST
  pacc-client.service              systemd 单元
  pacc-client.properties.example   配置示例
  install.sh                       安装脚本
  package.sh                       打包脚本
```

## 构建前置条件

- Rust 1.75 以上。本仓库在 1.98.1 上构建并测试过。
- **不需要联网**。整条依赖链是纯 std，没有第三方 crate，`cargo build` 不会去拉 registry。
- 要在 Linux 上跑才有意义：`/proc`、`/dev/input`、UNIX socket 都是 Linux 专有。在 Windows/macOS 上能编译、能过单元测试，但运行时事件源会如实报不可用。
- 交叉编译到 aarch64 请在 Linux 机器上做。eBPF/CO-RE 本来就依赖目标内核头文件。

```bash
cd platform/linux-client
cargo build --release
cargo test --release
# 产物：target/release/pacc-linux-client
```

## 安装

```bash
sudo ./install.sh --start                            # 用本目录 target/release 里的二进制
sudo ./install.sh --binary ./bin/pacc-linux-client   # 解压包里用包内二进制
```

`install.sh` 按顺序做这些事：建 system 用户 `pacc` → 装二进制到 `/usr/local/bin` → 建 `/etc/pacc`（0750 root:pacc），配置缺失时放一份模板（0640）→ 装 systemd 单元 → `daemon-reload` → `enable` → 按 `--start` 决定是否立刻启动。

幂等。已有账号不重建，已有 `/etc/pacc/pacc-client.properties` 不覆盖（里面可能有令牌，覆盖等于丢凭证）。参数还有 `--prefix`（默认 `/usr/local`）和 `--no-enable`。

脚本的 shebang 是 bash。在 Windows 上 checkout 会丢掉可执行位，Linux 上第一次用之前先 `chmod +x install.sh package.sh`，或者直接 `bash install.sh`。

手工安装等价于：二进制放 `/usr/local/bin/pacc-linux-client`，配置放 `/etc/pacc/pacc-client.properties`，单元放 `/etc/systemd/system/pacc-client.service`，然后 `systemctl daemon-reload && systemctl enable --now pacc-client`。

### 升级

```bash
systemctl stop pacc-client
sudo ./install.sh --start        # 覆盖二进制；/etc/pacc/pacc-client.properties 原样保留
```

配置不会被覆盖，所以升级不需要重新填令牌。如果新版加了配置键，旧文件里没有的键会走内置默认值——想拿到新模板就把它和 `pacc-client.properties.example` 对比后手动合并。

### 卸载

```bash
sudo systemctl disable --now pacc-client
sudo rm -f /etc/systemd/system/pacc-client.service /usr/local/bin/pacc-linux-client
sudo systemctl daemon-reload
sudo rm -rf /etc/pacc                 # 配置与模型，确认不再需要再删
sudo userdel pacc                     # 服务账号
```

## 运行

```bash
# 排障：只跑一轮就退出，看事件源选择和上报结果
pacc-linux-client --config /etc/pacc/pacc-client.properties --once

# 常驻交给 systemd
systemctl status pacc-client
journalctl -u pacc-client -f
journalctl -u pacc-client | grep 事件源选择      # 一眼看出走的 eBPF 还是 procfs
```

启动日志打三件关键信息：读的哪份配置（有没有读到）、上报端点（令牌配了没有）、事件源选了哪个以及为什么。事件源那行会写清楚是「socket 不存在」「内核版本低于 5.4」还是「进程缺 CAP_BPF」，不用猜。

`--once` 不进入周期循环，跑完一轮就退，适合改完配置先验一把。

## 配置

优先级 **环境变量 > 配置文件 > 内置默认值**，和 Java 端 `ClientConfig.get(...)` 一样。环境变量名 = 键名大写、点换下划线、加 `PACC_CLIENT_` 前缀。

| 键 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `pacc.client.server.uri` | `PACC_CLIENT_SERVER_URI` | `http://127.0.0.1:8080` | 后端基础地址，不含路径 |
| `pacc.client.events.path` | `PACC_CLIENT_EVENTS_PATH` | `/api/player/security/events` | 上报路径；以 `http://` 开头则视为完整 URL 并覆盖 `server.uri` |
| `pacc.client.token` | `PACC_CLIENT_TOKEN` | 空 | 作为 `Authorization: Bearer` 发送；空则匿名上报 |
| `pacc.client.pteid` | `PACC_CLIENT_PTEID` | `PT0000000001` | 玩家 ID，服务端校验令牌主体与之一致 |
| `pacc.client.edition` | `PACC_CLIENT_EDITION` | `LINUX` | 渠道标识，服务端 edition 白名单需包含 |
| `pacc.client.heartbeat.seconds` | `PACC_CLIENT_HEARTBEAT_SECONDS` | `15` | 检测与上报周期，1–3600，0 会被拒绝 |
| `pacc.client.max.events.per.report` | `PACC_CLIENT_MAX_EVENTS_PER_REPORT` | `20` | 单次上报最多带几条事件，超出记进 `total_events` |
| `pacc.client.http.timeout.seconds` | `PACC_CLIENT_HTTP_TIMEOUT_SECONDS` | `6` | HTTP 超时 |
| `pacc.client.report.only.suspicious` | `PACC_CLIENT_REPORT_ONLY_SUSPICIOUS` | `true` | 关掉就是全量上报，排障用 |
| `pacc.client.ebpf.enabled` | `PACC_CLIENT_EBPF_ENABLED` | `true` | eBPF 事件源开关 |
| `pacc.client.ebpf.socket` | `PACC_CLIENT_EBPF_SOCKET` | `/run/pacc/pacc-ldm.sock` | loader 的 UNIX socket |
| `pacc.client.procfs.enabled` | `PACC_CLIENT_PROCFS_ENABLED` | `true` | procfs 回退开关 |
| `pacc.client.model.path` | `PACC_CLIENT_MODEL_PATH` | 空 | 端侧 AI 模型文件；空则走回退，回退分不参与判定 |
| `pacc.client.model.signature` | `PACC_CLIENT_MODEL_SIGNATURE` | 空 | 模型期望签名，配了就强校验 |

另有 `PACC_CLIENT_CONFIG` 指定配置文件路径，等价于 `--config`。

`--config`（或 `PACC_CLIENT_CONFIG`）指到不存在的文件会**直接报错退出**，不会静默用默认值——否则运维改完配置发现没生效会很难查。

**只支持 `http://`**。零依赖构建里没有 TLS，写 `https://` 会明确报错，不会偷偷降级成明文。生产环境让客户端指向本机网关（比如 `http://127.0.0.1:8080`），由网关出公网。

配置里没有任何密钥默认值。缺令牌就是匿名上报，由服务端决定收不收。发行件里留明文密钥等于把伪造上报的钥匙发出去。

## 事件源选择

eBPF 要三个条件同时成立，缺一个就回退 procfs：

1. `pacc.client.ebpf.enabled` 为 true；
2. `pacc.client.ebpf.socket` 指向的文件存在，且确实是 socket 类型；
3. 内核版本 ≥ 5.4（BTF/CO-RE 的下限）。

回退不是悄悄降级：启动时会打印差在哪一条，包括 `/proc/self/status` 的 `CapEff` 里缺哪个能力位。回退后按 `heartbeat.seconds` 轮询，不会忙循环。

eBPF 侧断线（loader 重启、socket 被删）走指数退避：500ms 起，翻倍到 30s 封顶，连上立刻复位。socket 长时间不在时进程安静地等，不刷日志也不吃 CPU。

## 与 kernel-linux 的接口契约

`kernel-linux` 目前只有 LKM，没有 userspace loader。下面这套契约是客户端这一侧定的，loader 照此实现即可对接。

- 传输：`SOCK_STREAM` 的 UNIX socket，默认 `/run/pacc/pacc-ldm.sock`；
- 编码：NDJSON，一行一个 JSON 对象，`\n` 分帧；
- 单行上限 64 KiB，超了整段丢弃并计入解析错误（防畸形输入撑内存）；
- 字段全部可选、宽容解析。认不出的字段忽略，认不出的行只计数不转发：

| 字段 | 别名 | 说明 |
| --- | --- | --- |
| `event` | `event_type`、`type` | 事件类型（必需，没有就整行丢弃）|
| `pid` | `tgid` | 进程号 |
| `ppid` | | 父进程号 |
| `comm` | `process_name`、`name` | 进程名 |
| `path` | `exe`、`binary` | 可执行文件路径 |
| `uid` | | 用户 ID |
| `severity` | `level` | `high`/`medium`/`critical` 判为可疑；不填则按事件类型推断 |
| `ts` | `timestamp`、`time`、`ts_millis` | 时间戳 |
| `detail` | | 字符串或对象，原样带进上报体（对象会重新编码） |

`severity` 缺失时推断为 high 的事件类型：含 `ptrace`、`pvmread`、`process_vm_readv`、`devmem`、`/dev/mem`、`inject`、`dma`、`exec_anon`、`memfd`、`unlink_self`、`setuid` 的那些。

示例行：

```json
{"ts":1712000000,"event":"ptrace","pid":4242,"ppid":1,"comm":"gdb","path":"/usr/bin/gdb","uid":1000,"severity":"high","detail":{"target":1234}}
```

loader 的 socket 要对 `pacc` 用户可读写（`chgrp pacc` + `chmod 0660`），否则客户端连不上，会自动回退 procfs 并在日志里说明。

## 六大能力区域与 Windows 端对照（A17）

先把口径说清楚，不然数字没意义。

基线是 Windows 端：游戏 JVM 里的 java-agent 提供游戏内事件通道，CDP 内核驱动提供系统层通道，两者合起来填满 178 维。Linux 原生客户端在进程外，只有系统层面的只读取证。**它拿不到游戏内事件，这是架构决定的，不是没写完。**

因此下表的「对齐」分两列：

- **可达**：客户端已经实现的平台层 + rust-core 已实现的算法，接上线之后能覆盖的比例；
- **实测**：当前代码实际喂进 178 维的比例。两者差多少，就是还没接线或还没实现的量。

| 能力区域 | 维度 | Windows 端数据通道 | Linux 原生客户端数据通道 | 可达 | 实测 |
| --- | --- | --- | --- | --- | --- |
| 战斗 | 52 | agent 采集的点击/挥臂/攻击/命中事件 | `/dev/input` 能拿到点击与鼠标轨迹；攻击距离、暴击、目标锁定、命中判定要游戏内事件 | ~40% | 0% |
| 移动 | 44 | agent 采集的游戏 tick 位置/速度 | 无。位置与速度是游戏内数据 | ~3% | 0% |
| 环境设备 | 30 | 内核驱动 + WMI | `/proc` 枚举进程、`maps` 分类、`TracerPid`、内存/磁盘/uptime、hypervisor 位；SSDT hook、驱动签名、PCIe DMA、IOMMU、硬件断点这些是 Windows 内核面独有 | ~70% | ~70% |
| JVM | 20 | agent 读 JMX/内部计数器 | 无。rust-core 也没实现这一组 | 0% | 0% |
| 模组 | 15 | agent 扫 jar 清单与签名 | 无。同上 | 0% | 0% |
| 网络 | 17 | agent 侧包统计 | 无。`/proc/net/dev` 都没接进来 | 0% | 0% |

按维度加权：**可达 ≈ 25%，实测 ≈ 7%**（分子主要是环境设备组里真正在跑的那 13 个维度）。

**结论：A17 的「≥ Windows 端 80%」目前不成立，本模块单独做不到。** 缺口有两块，性质不同：

1. **接线的活**（战斗组）。平台层的 `sample_input_events` 已经能从 `/dev/input` 读原始事件，collector 也有 `record_click`/`record_mouse`，但 `main.rs` 没把两者接起来。这一段是可达的，接上战斗组就能到 ~40%。
2. **架构与实现的活**（移动/JVM/模组/网络）。移动组要游戏内 tick 数据，进程外拿不到；JVM/模组/网络三组 rust-core 根本没实现。这四组靠本模块补不上。

需要指出的是：Linux 上的实际部署如果**同时**跑 java-agent（游戏本身是 JVM 程序），那 JVM 与游戏内事件通道就又有了，整体对齐会接近 Windows。但那是部署组合论证，不是本模块的属性，本目录也没有测过。

非特征维度的能力对照：

| 能力 | Windows | Linux 本客户端 |
| --- | --- | --- |
| 进程枚举 | ✅ | ✅ `/proc` |
| 内存扫描 | ✅ | ✅ `maps` 分类：`rwx` / `memfd:` / `(deleted)` |
| 输入事件采样 | ✅ | ⚠️ 平台层能读 `/dev/input`，主循环未接线 |
| 反调试探针 | ✅ | ✅ `TracerPid` / yama |
| 安装完整性校验 | ✅ | ✅ 平台 trait 已实现（CRC32 清单） |
| 内核级钩子 | ✅ CDP | ⚠️ 依赖 kernel-linux 的 loader 提供 socket |
| JVM 字节码完整性 | ✅ | ❌ |
| 网络包特征 | ✅ | ❌ |

procfs 有几处是**故意不报**的，免得把正常玩家打成可疑：匿名 `r-xp` 无路径映射只计数不出事件（JVM 的 JIT 代码缓存、`[vdso]` 都是这个形态）；本进程自己跳过检查。

## 权限要求

| 用途 | 需要 | 没有时的后果 |
| --- | --- | --- |
| 加载 / 操作 eBPF | `CAP_BPF` 或 `CAP_PERFMON`（5.8 以前是 `CAP_SYS_ADMIN`） | loader 起不来 → 回退 procfs |
| 读别的用户的 `/proc/<pid>/maps` | `CAP_SYS_PTRACE`（或同 uid） | 只能看自己 uid 的进程映射，判据大幅缩水 |
| 读 `/dev/input/event*` | `input` 组 或 root | 拿不到原始输入事件 |
| 连 loader 的 socket | socket 对 `pacc` 用户可读写 | 回退 procfs |
| 读配置与模型 | 文件对 `pacc` 可读 | 起不来 |

单元里给的是 `AmbientCapabilities=CAP_BPF CAP_PERFMON CAP_SYS_PTRACE`，`CapabilityBoundingSet` 同步收窄，账号是非 root 的 `pacc`。

单元里刻意没设 `ProtectProc=invisible`：procfs 回退要读别人的 `/proc/<pid>/{cmdline,maps}`，把 `/proc` 藏起来等于把回退事件源废掉。同理没设 `MemoryDenyWriteExecute`。

## 打包

```bash
./package.sh 5.4.0          # 版本号作为第一个参数
./package.sh                # 不给就用 Cargo.toml 里的版本，读不到回退 5.4.0
./package.sh 5.4.0 --no-build
```

产物（路径固定，`../../scripts/build-all.sh` 第 7 步依赖）：

```
target/dist/pacc-linux-5.4.0.tar.gz     始终产出
target/dist/pacc-linux-5.4.0.deb        有 dpkg-deb 时产出
target/dist/pacc-linux-5.4.0.rpm        有 rpmbuild 时产出
```

tar.gz 里是 `bin/pacc-linux-client`、`pacc-client.service`、`pacc-client.properties.example`、`install.sh`、`README.md`、`BUILD-INFO`。解压后 `cd pacc-linux-5.4.0 && sudo ./install.sh --binary ./bin/pacc-linux-client`。

缺 `dpkg-deb` / `rpmbuild` 只打印原因并跳过，不算失败——纯 CI runner 上这俩经常没有，不该卡住发布流水线。真正的失败（构建挂了、二进制没生成）一律非零退出，让 build-all.sh 的 `set -e` 拦住。

`.deb` 的 postinst 会建账号、放配置模板、`daemon-reload`，但不自动 enable；装包和玩家授权常驻是两件事。`.rpm` 同理。

## 验证状态

### 已验证（本机实测）

命令与结果都是真实输出，开发机为 Windows 11 + rustc 1.98.1 + clippy 0.1.98。

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 核心构建 | `cargo build --workspace --release`（rust-core） | 通过，24.3s |
| 核心测试 | `cargo test --workspace`（rust-core） | **34 passed / 0 failed** |
| 核心 lint | `cargo clippy --workspace --all-targets -- -D warnings` | 通过，0 error 0 warning |
| 客户端构建 | `cargo build --release`（linux-client） | 通过 |
| 客户端测试 | `cargo test --release` | **35 passed / 0 failed** |
| 客户端 lint | `cargo clippy --workspace --all-targets -- -D warnings` | 通过，0 error 0 warning |
| 脚本语法 | `bash -n package.sh` / `bash -n install.sh` | 均通过 |
| 打包 | `./package.sh`（不给参数，从 Cargo.toml 取版本） | 产出 `target/dist/pacc-linux-5.4.0.tar.gz`，195K |
| 打包（显式版本） | `./package.sh 5.4.0 --no-build` | 产出同一路径，退出码 0 |
| A21 基准 | `cargo bench -p pacc-core` | 见下 |

A21 实测（x86_64 / Windows / release，512 采样滑窗）：

```
Collector::collect()                 194.3 us/op      5147 ops/s
collect + apply + evaluate           174.1 us/op      5745 ops/s
PaccCore::snapshot() [计算]           264.0 us/op      3788 ops/s
```

那个 174µs 看着不低，所以我查了一遍 `pacc-collector/src/stats.rs` 里的均值/方差/偏度/峰度/熵——全部是 O(n)，没有把 `mean()` 塞进循环这种 O(n²) 写法。耗时主要在三点：`collect()` 每轮克隆 7 个 512 元素缓冲、轨迹分析里有三趟带 `atan2`/`sqrt` 的循环、以及几十个小 `Vec` 分配。按 15 秒上报周期算，264µs 一轮完全不是瓶颈。

**A21 的「比 Java 快 ≥30%」这一条没有拿到证据。** 基准只量了 Rust 侧。同机对比要跑 Java 端 `FeatureCollector.collect()`（`ptv-client/src/main/java/com/potatotv/paccclient/detection/`），本轮没做，所以不编造比值。要补的话：

```bash
# 在 Linux/Windows 上用同一份负载各跑 N 次，比 [1]/[2] 两段
cd platform/rust-core && PACC_BENCH_ITERS=50000 cargo bench -p pacc-core
```

对比时只能比基准的 `[1]`/`[2]` 两段。`[3]` 是否含系统 I/O 取决于平台实现：Linux 上是真实 `/proc` 读取，其余平台是如实返回 `Unsupported` 的桩，几乎没有 I/O。

### 未验证（需 Linux 维护者执行）

下面这些在本机无法观察，一律没有声称过通过：

- **运行时行为**。`/proc` 与 `/dev/input` 读取、`TracerPid`/yama 判定、eBPF socket 连接与退避重连、信号收敛，这些都只在 Linux 上有意义。本机只做到编译期验证（含 `#[cfg(target_os = "linux")]` 分支的语法）与单元测试。
- **systemd 单元**。`pacc-client.service` 没在真实 systemd 上 start/stop 过，`AmbientCapabilities`、`ProtectSystem=strict` 与 `pacc` 用户的实际权限组合未验证。
- **`install.sh` 端到端**。建账号、改权限、`daemon-reload`、`enable` 这些只在 Linux 上跑得动。
- **`.deb` / `.rpm` 的构建与安装**。本机没有 `dpkg-deb` 与 `rpmbuild`，脚本按设计跳过并打了原因。`postinst`/`prerm` 没执行过。
- **tar.gz 内容**。本机跑出的包内二进制是 `pacc-linux-client.exe`（Git Bash 的 MSYS 会把无后缀名解析到 `.exe`），这是因为在 Windows 上构建。Linux 上构建出来的是无后缀的 `pacc-linux-client`。包结构与脚本逻辑已验证，二进制本身不是 Linux 产物。
- **eBPF 路径**。`kernel-linux` 还没有 userspace loader，那套 socket/NDJSON 契约是客户端这边定的，双向都没跑过。实际部署目前只会走 procfs。
- **A17 的 80%**。见上文，本模块单独达不到，且两个口径的百分比都是静态推算，没在真实对局里标定过。生产环境建议：让客户端指向本机网关（别直连公网）、令牌与 PTEID 按实际玩家填、先在单机跑 `--once` 确认事件源选择与上报返回码。