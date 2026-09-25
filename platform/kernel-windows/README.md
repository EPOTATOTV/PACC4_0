# PACC 内核驱动（Windows CDP）

底层检测层（Windows），基于 WDM + 对象回调 + 内存取证，已实现为完整驱动逻辑源码。
真机部署需在 WDK10 + EV/WHQL 签名环境下用 `msbuild` 编译与签名。

## 能力
- **进程对象回调**：`ObRegisterCallbacks` 拦截对受保护进程的高危句柄访问
  （`PROCESS_VM_WRITE/VIRTUAL_MEMORY_OPERATION/CREATE_THREAD/SUSPEND` 等），
  非授权请求记入拦截计数并可裁剪权限，防注入/Hack。
- **内存特征扫描**：附加目标进程分块读取，通配符 `??` 逐字节比对，返回命中地址。
- **设备白名单**：校验 USB/HID 设备实例 ID 是否在注册表白名单内（宏键盘识别）。
- **IOCTL 协议**：受保护 PID 登记、内存扫描、拦截统计查询，供 ptv-client 驱动接口对接。
- **进程创建监控**：`PsSetCreateProcessNotifyRoutineEx`，用小尺寸可疑名单
  （调试器 / 注入工具 / 已知作弊加载器）标记启动，上报映像路径 + PID + 父 PID。
- **镜像加载监控**：`PsSetLoadImageNotifyRoutine`，识别未签名、临时目录路径、
  已知恶意基名前缀的 DLL/驱动加载，并标记注入保护进程的镜像。
- **线程创建监控**：`PsSetCreateThreadNotifyRoutine`，记录受保护进程内部新建线程
  （远程线程注入指示）。
- **注册表监控**：`CmRegisterCallback`，盯防 Image File Execution Options（调试器劫持）
  与 AppInit_DLLs（全局注入），上报键路径与被写入的值。
- **SSDT 完整性**：加载时定位并快照系统服务分派表条目，按需重读比对，报告分歧（内核 Hook 痕迹）。
- **驱动枚举 + 签名核验**：遍历 `PsLoadedModuleList`，读取每个映像的 PE 证书目录，
  标记未签名 / 非 Microsoft 驱动（Rootkit 检测）。

## 目录结构
```
kernel-windows/
  pacc_common.h     -> 公共头（IOCTL / 结构 / 全局）
  pacc_driver.c     -> 驱动主入口（DriverEntry / IRP 分发 / 保护集合）
  pacc_event.c      -> 有界事件环形缓冲 + 内核侧字符串工具
  pacc_obhook.c     -> 对象回调（NtOpenProcess/DuplicateHandle 拦截）
  pacc_scanner.c    -> 内存特征扫描（分块 + ?? 通配）
  pacc_device.c     -> 设备白名单校验（注册表持久化）
  pacc_procmon.c    -> 进程 / 线程创建监控
  pacc_imagemon.c   -> 镜像（DLL / 驱动）加载监控
  pacc_regmon.c     -> 注册表监控（IFEO / AppInit_DLLs）
  pacc_ssdt.c       -> SSDT 完整性校验（快照 + 比对）
  pacc_drivenum.c   -> 已加载驱动枚举 + 签名核验
  sources           -> WDK 构建清单
```

## 事件环形缓冲
- 事件统一为定长 `PACC_EVENT`（类别 / 标志 / PID / 父 PID / 时间戳 / 数值 / 明细串）。
- 缓冲在 `DriverEntry` 于**非分页池**一次性预分配 `PACC_EVENT_RING_CAPACITY = 256` 条，
  运行时**绝不增长**；全部读写受一把 `KSPIN_LOCK` 保护，可在 DISPATCH_LEVEL 回调中安全入队。
- **丢弃策略**：写满时丢弃最旧事件（Head 前移）并累加 `Dropped`——被洪泛也无法耗尽池。
- 所有内核通知回调内只做内存拷贝与入队，**不做任何 I/O / 阻塞等待**。

## IOCTL 协议（METHOD_BUFFERED）
| IOCTL | 输入 | 输出 | 说明 |
|-|-|-|-|
| 参见既有定义 | | | `0x8000+1..+4`：登记/清空保护 PID、内存扫描、拦截统计 |
| `+5` DRAIN_PROCESS | `PACC_DRAIN_REQUEST` | `PACC_EVENT_BATCH` + N×`PACC_EVENT` | 排空进程创建事件 |
| `+6` DRAIN_IMAGE | 同上 | 同上 | 排空镜像加载事件 |
| `+7` DRAIN_THREAD | 同上 | 同上 | 排空线程创建事件 |
| `+8` DRAIN_REGISTRY | 同上 | 同上 | 排空注册表事件 |
| `+9` RESET_EVENT_COUNTERS | 无 | `PACC_EVENT_STATS` | 归零全部计数器并返回快照 |
| `+10` QUERY_SSDT_STATUS | 无 | `PACC_SSDT_STATUS` | SSDT 条目数 / 分歧数 / 首个分歧项 |
| `+11` QUERY_DRIVER_STATUS | `PACC_DRIVER_QUERY` | `PACC_DRIVER_REPORT` | 驱动枚举 + 签名核验（`Entries` 变长，分页） |

排空接口的用户侧 `MaxCount` 仅作上限参考，实际条数由输出缓冲与环形容量共同约束；
输出缓冲必须容纳头部 + 至少一条 `PACC_EVENT`。驱动状态查询以 `StartIndex/MaxCount` 分页，
单次上限 `PACC_DRIVER_MAX_PER_CALL`。

## 构建（需 Windows Driver Kit 10）
```bat
:: 在 "x64" 开发者命令提示符下
msbuild /t:build /p:Configuration=Release /p:TargetVersion=Win10
```
产出 `PaccPtv.sys`，经 EV 签名 + WHQL 后随玩家端安装包发布。

## 说明
本源码为反作弊取证用途：仅当玩家显式安装反作弊系统且同意其条款时部署。
拦截策略默认"记录优先、按需拦截"，避免误伤正常系统与开发工具。