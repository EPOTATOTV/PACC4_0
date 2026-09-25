// pacc_common.h - PACC 底层检测驱动公共定义（Windows WDM / CDP）
#pragma once

#include <ntddk.h>
#include <wdm.h>

// ---- 设备 / 符号链接 ----
#define PACC_DEVICE_NAME        L"\\Device\\PaccPtv"
#define PACC_SYMLINK_NAME       L"\\??\\PaccPtv"
#define PACC_DOSNAME            L"\\DosDevices\\PaccPtv"

// ---- 被保护进程白名单容量 ----
#define PACC_MAX_PROTECTED_PID 64

// ---- IOCTL 协议（与应用层 ptv-client 对应）----
#define PACC_IOCTL_BASE       0x8000

// 登记受保护进程 PID（输入 ULONG）
#define IOCTL_PACC_ADD_PROTECTED_PID \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 1, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 清除全部受保护 PID
#define IOCTL_PACC_CLEAR_PROTECTED_PIDS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 2, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 触发一次内存特征扫描（输入 PACC_SCAN_REQUEST，输出 PACC_SCAN_RESULT）
#define IOCTL_PACC_SCAN_MEMORY \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 3, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 查询自上次以来累计的拦截事件数（输出 ULONG64）
#define IOCTL_PACC_QUERY_BLOCK_STATS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 4, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 排空进程创建事件（输入 PACC_DRAIN_REQUEST，输出 PACC_EVENT_BATCH + N*PACC_EVENT）
#define IOCTL_PACC_DRAIN_PROCESS_EVENTS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 5, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 排空镜像（DLL/驱动）加载事件
#define IOCTL_PACC_DRAIN_IMAGE_EVENTS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 6, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 排空线程创建事件（仅保护进程内部线程）
#define IOCTL_PACC_DRAIN_THREAD_EVENTS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 7, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 排空注册表监控事件（IFEO / AppInit_DLLs）
#define IOCTL_PACC_DRAIN_REGISTRY_EVENTS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 8, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 归零全部事件/命中计数器（输出 PACC_EVENT_STATS，返回归零后的快照）
#define IOCTL_PACC_RESET_EVENT_COUNTERS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 9, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 查询 SSDT 完整性状态（输出 PACC_SSDT_STATUS）
#define IOCTL_PACC_QUERY_SSDT_STATUS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 10, METHOD_BUFFERED, FILE_ANY_ACCESS)

// 查询已加载驱动枚举 + 签名核验（输入 PACC_DRIVER_QUERY，输出 PACC_DRIVER_REPORT）
#define IOCTL_PACC_QUERY_DRIVER_STATUS \
    CTL_CODE(FILE_DEVICE_UNKNOWN, PACC_IOCTL_BASE + 11, METHOD_BUFFERED, FILE_ANY_ACCESS)

// ---- 数据结构 ----
typedef struct _PACC_SCAN_REQUEST {
    ULONG Pid;                  // 目标进程
    PVOID Pattern;              // 特征模式（请求者缓冲区，由驱动拷贝/固定）
    SIZE_T PatternLength;       // 模式字节数
} PACC_SCAN_REQUEST, *PPACC_SCAN_REQUEST;

typedef struct _PACC_SCAN_RESULT {
    BOOLEAN Hit;               // 是否命中
    ULONG64 RegionBase;        // 命中地址（相对目标进程）
    SIZE_T RegionSize;
} PACC_SCAN_RESULT, *PPACC_SCAN_RESULT;

// ============================================================================
// 事件监视协议（Alpha 1.0.0 / DF）
// ============================================================================

// ---- 事件类别（同时用作环形缓冲的类别过滤码与命中计数下标，1..4）----
#define PACC_EVENT_CLASS_PROCESS   1
#define PACC_EVENT_CLASS_IMAGE     2
#define PACC_EVENT_CLASS_THREAD    3
#define PACC_EVENT_CLASS_REGISTRY  4
#define PACC_EVENT_CLASS_MAX       4
#define PACC_EVENT_CLASS_ALL       0   // 排空过滤器：全部类别

// ---- 事件标志位 ----
#define PACC_EVENT_FLAG_SUSPICIOUS       0x00000001  // 命中可疑名单
#define PACC_EVENT_FLAG_UNSIGNED         0x00000002  // 未签名 / 签名级别不可信
#define PACC_EVENT_FLAG_TEMP_DIR         0x00000004  // 临时目录路径
#define PACC_EVENT_FLAG_KNOWN_BAD        0x00000008  // 命中已知恶意镜像前缀
#define PACC_EVENT_FLAG_INTO_PROTECTED   0x00000010  // 加载进保护进程
#define PACC_EVENT_FLAG_FROM_PROTECTED   0x00000020  // 由保护进程发起
#define PACC_EVENT_FLAG_IFEO             0x00000040  // Image File Execution Options
#define PACC_EVENT_FLAG_APPINIT          0x00000080  // AppInit_DLLs
#define PACC_EVENT_FLAG_KERNEL_IMAGE     0x00000100  // 内核态镜像（驱动）

// ---- 有界环形事件缓冲 ----
// 预分配于非分页池；写满时丢弃最旧事件并累加 Dropped，绝不增长。
#define PACC_EVENT_RING_CAPACITY 256
#define PACC_EVENT_DETAIL_MAX    260   // WCHAR 数（含终止符）

typedef struct _PACC_EVENT {
    ULONG   Class;          // PACC_EVENT_CLASS_*
    ULONG   Flags;          // PACC_EVENT_FLAG_*
    ULONG   Pid;            // 主体进程 PID
    ULONG   ParentPid;      // 父进程 PID（进程事件）或保留
    ULONG64 TimeStamp;      // KeQueryInterruptTime（100ns，系统运行时长）
    ULONG64 Aux1;           // 类别相关：线程事件=线程 ID；SSDT=序号
    ULONG64 Aux2;           // 类别相关：附加数值
    WCHAR   Detail[PACC_EVENT_DETAIL_MAX];  // 镜像路径 / 键路径 / 值名
} PACC_EVENT, *PPACC_EVENT;

// 排空请求（输入）
typedef struct _PACC_DRAIN_REQUEST {
    ULONG MaxCount;         // 期望最多取回条数（受容量与输出缓冲双向约束）
    ULONG Reserved;
} PACC_DRAIN_REQUEST, *PPACC_DRAIN_REQUEST;

// 排空回复头（输出；其后紧跟 Count 个 PACC_EVENT）
typedef struct _PACC_EVENT_BATCH {
    ULONG   Class;
    ULONG   Count;
    ULONG64 Dropped;        // 自加载以来因缓冲满而丢弃的旧事件累计
} PACC_EVENT_BATCH, *PPACC_EVENT_BATCH;

// 计数器快照（亦为重置回复）
typedef struct _PACC_EVENT_STATS {
    ULONG64 ClassCount[PACC_EVENT_CLASS_MAX + 1]; // 各类别累计入队（下标 1..4）
    ULONG64 Pushed;                                // 累计入队总数
    ULONG64 Dropped;                               // 累计丢弃数
    ULONG64 Capacity;                              // 环形容量
    ULONG64 Suspicious[PACC_EVENT_CLASS_MAX + 1];  // 各监视器可疑命中
} PACC_EVENT_STATS, *PPACC_EVENT_STATS;

// ---- SSDT 完整性状态 ----
typedef struct _PACC_SSDT_STATUS {
    BOOLEAN  Supported;             // 是否成功定位到服务表
    BOOLEAN  Shadow;                // 保留
    ULONG    EntryCount;            // 快照条目数
    ULONG    DivergenceCount;       // 与快照不一致的条目数
    ULONG    FirstDivergentIndex;   // 首个不一致条目序号（无则 0xFFFFFFFF）
    ULONG    Reserved;
    ULONG64  NtoskrnlBase;
    ULONG64  NtoskrnlSize;
    ULONG64  DescriptorVa;
    ULONG64  TableVa;
    ULONG64  SnapshotVa;
    ULONG64  FirstSnapshotValue;    // 首个不一致条目的快照值
    ULONG64  FirstCurrentValue;     // 首个不一致条目的当前值
} PACC_SSDT_STATUS, *PPACC_SSDT_STATUS;

// ---- 已加载驱动枚举 + 签名核验 ----
#define PACC_DRIVER_MAX_PER_CALL 64
#define PACC_DRIVER_PATH_MAX     260
#define PACC_DRIVER_SIGNER_MAX   128

#define PACC_DRIVER_FLAG_SIGNED         0x00000001  // 存在嵌入证书（已签名）
#define PACC_DRIVER_FLAG_UNSIGNED       0x00000002  // 无证书（未签名）
#define PACC_DRIVER_FLAG_MICROSOFT      0x00000004  // 签名主体含 Microsoft
#define PACC_DRIVER_FLAG_NON_MICROSOFT  0x00000008  // 已签名但非 Microsoft
#define PACC_DRIVER_FLAG_SUSPECT        0x00000010  // 未签名 / 非微软 / 读取失败 / 命中名单

typedef struct _PACC_DRIVER_QUERY {
    ULONG StartIndex;               // 从第几个已加载模块开始（分页）
    ULONG MaxCount;                 // 期望条数（受 PACC_DRIVER_MAX_PER_CALL 与缓冲约束）
} PACC_DRIVER_QUERY, *PPACC_DRIVER_QUERY;

typedef struct _PACC_DRIVER_ENTRY {
    WCHAR   ImagePath[PACC_DRIVER_PATH_MAX];
    WCHAR   SignerName[PACC_DRIVER_SIGNER_MAX];  // 签名主体 CN（启发式提取）
    WCHAR   IssuerName[PACC_DRIVER_SIGNER_MAX];  // 签发者 CN（启发式提取）
    ULONG64 Base;
    ULONG64 Size;
    ULONG   Flags;                  // PACC_DRIVER_FLAG_*
    ULONG   Reserved;
} PACC_DRIVER_ENTRY, *PPACC_DRIVER_ENTRY;

typedef struct _PACC_DRIVER_REPORT {
    ULONG TotalLoaded;              // 系统已加载模块总数
    ULONG Returned;                 // 本次返回条数
    ULONG NextIndex;                // 下一页起始下标；== TotalLoaded 表示已到末尾
    ULONG Suspicious;               // 本次返回页内被判可疑的条数
    PACC_DRIVER_ENTRY Entries[1];   // 变长
} PACC_DRIVER_REPORT, *PPACC_DRIVER_REPORT;

// 保护进程集合（全局，共用）
extern volatile ULONG  g_paccProtectedPids[PACC_MAX_PROTECTED_PID];
extern volatile ULONG64 g_paccBlockedOpCount;     // 被拦截的越权访问计数
extern volatile ULONG64 g_paccDeniedLogCount;
extern BOOLEAN          g_paccInitialized;

BOOLEAN PaccIsProtectedPid(ULONG pid);
VOID    PaccAddProtectedPid(ULONG pid);
VOID    PaccClearProtectedPids(VOID);

// ---- 内核侧字符串工具（pacc_event.c）----
BOOLEAN PaccStrEqualCI(PCWSTR a, PCWSTR b);
BOOLEAN PaccStrContainsCI(PCWSTR haystack, PCWSTR needle);
VOID    PaccCopyUnicode(PCUNICODE_STRING src, PWCHAR dst, ULONG cchDst);

// ---- 有界事件环形缓冲（pacc_event.c）----
NTSTATUS PaccEventInitialize(VOID);
VOID     PaccEventUninitialize(VOID);
VOID     PaccEventPush(ULONG eventClass, ULONG flags, ULONG pid, ULONG parentPid,
                       ULONG64 aux1, ULONG64 aux2, PCWSTR detail);
ULONG    PaccEventDrain(ULONG eventClass, PPACC_EVENT out, ULONG maxCount);
VOID     PaccEventResetCounters(VOID);
VOID     PaccEventGetStats(PPACC_EVENT_STATS out);
VOID     PaccMonitorHitInc(ULONG eventClass);

// 各子模块实现
NTSTATUS PaccObHookInitialize(VOID);
VOID     PaccObHookUninitialize(VOID);
NTSTATUS PaccScanMemory(PPACC_SCAN_REQUEST, PPACC_SCAN_RESULT);
BOOLEAN  PaccDeviceIsAllowed(PCWSTR devInstanceId, ULONG deviceIdLength);

// ---- 监视器（pacc_procmon.c / pacc_imagemon.c / pacc_regmon.c）----
NTSTATUS PaccProcessMonInitialize(VOID);
VOID     PaccProcessMonUninitialize(VOID);
NTSTATUS PaccImageMonInitialize(VOID);
VOID     PaccImageMonUninitialize(VOID);
NTSTATUS PaccRegMonInitialize(VOID);
VOID     PaccRegMonUninitialize(VOID);

// ---- SSDT 完整性（pacc_ssdt.c）----
NTSTATUS PaccSsdtInitialize(VOID);
VOID     PaccSsdtUninitialize(VOID);
NTSTATUS PaccSsdtQuery(PPACC_SSDT_STATUS out);

// ---- 已加载驱动枚举 + 签名核验（pacc_drivenum.c）----
NTSTATUS PaccDriverScanQuery(PPACC_DRIVER_QUERY req, PPACC_DRIVER_REPORT out, ULONG outLen);