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

// 保护进程集合（全局，共用）
extern volatile ULONG  g_paccProtectedPids[PACC_MAX_PROTECTED_PID];
extern volatile ULONG64 g_paccBlockedOpCount;     // 被拦截的越权访问计数
extern volatile ULONG64 g_paccDeniedLogCount;
extern BOOLEAN          g_paccInitialized;

BOOLEAN PaccIsProtectedPid(ULONG pid);
VOID    PaccAddProtectedPid(ULONG pid);
VOID    PaccClearProtectedPids(VOID);

// 各子模块实现
NTSTATUS PaccObHookInitialize(VOID);
VOID     PaccObHookUninitialize(VOID);
NTSTATUS PaccScanMemory(PPACC_SCAN_REQUEST, PPACC_SCAN_RESULT);
BOOLEAN  PaccDeviceIsAllowed(PCWSTR devInstanceId, ULONG deviceIdLength);