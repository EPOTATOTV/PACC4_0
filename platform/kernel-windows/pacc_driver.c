// pacc_driver.c - PACC 底层检测驱动主入口（Windows CDP / 内核回调）
// 职责：创建设备 → 分发 IOCTL（保护登记/内存扫描/拦截统计）→ 注册对象回调。
#include "pacc_common.h"
#include <ntstrsafe.h>

// ---- 全局（见 pacc_common.h）----
volatile ULONG   g_paccProtectedPids[PACC_MAX_PROTECTED_PID] = {0};
volatile ULONG64 g_paccBlockedOpCount = 0;
volatile ULONG64 g_paccDeniedLogCount = 0;
BOOLEAN          g_paccInitialized   = FALSE;

// ---- 受保护进程集合操作 ----
BOOLEAN
PaccIsProtectedPid(ULONG pid)
{
    if (pid == 0) return FALSE;
    for (ULONG i = 0; i < PACC_MAX_PROTECTED_PID; i++) {
        ULONG p = (ULONG)InterlockedExchange((volatile LONG *)&g_paccProtectedPids[i], (LONG)g_paccProtectedPids[i]);
        if (p == pid) return TRUE;
    }
    return FALSE;
}

VOID
PaccAddProtectedPid(ULONG pid)
{
    if (pid == 0) return;
    for (ULONG i = 0; i < PACC_MAX_PROTECTED_PID; i++) {
        ULONG cur = (ULONG)InterlockedCompareExchange((volatile LONG *)&g_paccProtectedPids[i], 0, 0);
        if (cur == pid) return;          // 已存在
        if (cur == 0) {
            InterlockedCompareExchange((volatile LONG *)&g_paccProtectedPids[i], (LONG)pid, 0);
            return;
        }
    }
}

VOID
PaccClearProtectedPids(VOID)
{
    for (ULONG i = 0; i < PACC_MAX_PROTECTED_PID; i++) {
        InterlockedExchange((volatile LONG *)&g_paccProtectedPids[i], 0);
    }
}

// ---- IRP 完成辅助 ----
NTSTATUS
PaccCompleteIrp(PIRP irp, NTSTATUS status, ULONG info)
{
    irp->IoStatus.Status = status;
    irp->IoStatus.Information = info;
    IoCompleteRequest(irp, IO_NO_INCREMENT);
    return status;
}

NTSTATUS
PaccDispatchCreateClose(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    UNREFERENCED_PARAMETER(DeviceObject);
    return PaccCompleteIrp(Irp, STATUS_SUCCESS, 0);
}

NTSTATUS
PaccDispatchDeviceControl(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    UNREFERENCED_PARAMETER(DeviceObject);
    PIO_STACK_LOCATION irpSp = IoGetCurrentIrpStackLocation(Irp);
    ULONG code = irpSp->Parameters.DeviceIoControl.IoControlCode;
    PVOID in = Irp->AssociatedIrp.SystemBuffer;
    ULONG inLen = irpSp->Parameters.DeviceIoControl.InputBufferLength;
    NTSTATUS status = STATUS_INVALID_DEVICE_REQUEST;
    ULONG info = 0;

    switch (code) {
    case IOCTL_PACC_ADD_PROTECTED_PID: {
        if (inLen >= sizeof(ULONG)) {
            PaccAddProtectedPid(*(ULONG *)in);
            status = STATUS_SUCCESS;
        }
        break;
    }
    case IOCTL_PACC_CLEAR_PROTECTED_PIDS:
        PaccClearProtectedPids();
        status = STATUS_SUCCESS;
        break;

    case IOCTL_PACC_SCAN_MEMORY: {
        if (inLen >= sizeof(PACC_SCAN_REQUEST)) {
            PPACC_SCAN_REQUEST req = (PPACC_SCAN_REQUEST)in;
            // 从 SystemBuffer（METHOD_BUFFERED）读取模式字节到非分页池
            ULONG patLen = (ULONG)req->PatternLength;
            if (patLen > 0 && patLen <= 4096 && inLen >= sizeof(PACC_SCAN_REQUEST) + patLen) {
                UCHAR *pattern = (UCHAR *)((PUCHAR)in + sizeof(PACC_SCAN_REQUEST));
                PACC_SCAN_REQUEST fixedReq;
                RtlZeroMemory(&fixedReq, sizeof(fixedReq));
                fixedReq.Pid = req->Pid;
                fixedReq.Pattern = pattern;
                fixedReq.PatternLength = patLen;

                PACC_SCAN_RESULT res;
                RtlZeroMemory(&res, sizeof(res));
                status = PaccScanMemory(&fixedReq, &res);
                if (NT_SUCCESS(status)) {
                    __try {
                        // METHOD_BUFFERED：输出复用 SystemBuffer 前缀位置
                        RtlCopyMemory(Irp->AssociatedIrp.SystemBuffer, &res, sizeof(res));
                        info = sizeof(res);
                    } __except(EXCEPTION_EXECUTE_HANDLER) {
                        status = STATUS_INVALID_USER_BUFFER;
                    }
                }
            } else {
                status = STATUS_BUFFER_TOO_SMALL;
            }
        } else {
            status = STATUS_BUFFER_TOO_SMALL;
        }
        break;
    }

    case IOCTL_PACC_QUERY_BLOCK_STATS: {
        if (Irp->AssociatedIrp.SystemBuffer && irpSp->Parameters.DeviceIoControl.OutputBufferLength >= sizeof(ULONG64) * 2) {
            ULONG64 stats[2] = { (ULONG64)InterlockedCompareExchange64((volatile LONG64 *)&g_paccBlockedOpCount, 0, 0),
                                 (ULONG64)InterlockedCompareExchange64((volatile LONG64 *)&g_paccDeniedLogCount, 0, 0) };
            RtlCopyMemory(Irp->AssociatedIrp.SystemBuffer, stats, sizeof(stats));
            info = sizeof(stats);
            status = STATUS_SUCCESS;
        }
        break;
    }

    default:
        status = STATUS_INVALID_DEVICE_REQUEST;
        break;
    }

    return PaccCompleteIrp(Irp, status, info);
}

NTSTATUS
PaccDispatchDefault(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    UNREFERENCED_PARAMETER(DeviceObject);
    return PaccCompleteIrp(Irp, STATUS_NOT_SUPPORTED, 0);
}

// 设备对象创建（WDK 场景按需回调）。
VOID
PaccCreateDevice(PDRIVER_OBJECT DriverObject)
{
    UNICODE_STRING name = RTL_CONSTANT_STRING(PACC_DEVICE_NAME);
    UNICODE_STRING link = RTL_CONSTANT_STRING(PACC_SYMLINK_NAME);
    PDEVICE_OBJECT dev = NULL;
    NTSTATUS status = IoCreateDevice(DriverObject, 0, &name, FILE_DEVICE_UNKNOWN,
                                     FILE_DEVICE_SECURE_OPEN, FALSE, &dev);
    if (!NT_SUCCESS(status) || dev == NULL) {
        DbgPrint("[PACC] IoCreateDevice failed %x\n", status);
        return;
    }
    status = IoCreateSymbolicLink(&link, &name);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] IoCreateSymbolicLink failed %x\n", status);
    }
    dev->Flags |= DO_BUFFERED_IO;
    dev->Flags &= ~DO_DEVICE_INITIALIZING;
}

VOID
PaccUnload(PDRIVER_OBJECT DriverObject)
{
    UNICODE_STRING link = RTL_CONSTANT_STRING(PACC_SYMLINK_NAME);
    IoDeleteSymbolicLink(&link);
    PaccObHookUninitialize();
    if (DriverObject->DeviceObject) {
        IoDeleteDevice(DriverObject->DeviceObject);
    }
    g_paccInitialized = FALSE;
    DbgPrint("[PACC] unloaded\n");
}

NTSTATUS
DriverEntry(PDRIVER_OBJECT DriverObject, PUNICODE_STRING RegistryPath)
{
    UNREFERENCED_PARAMETER(RegistryPath);
    NTSTATUS status;

    PaccClearProtectedPids();
    g_paccBlockedOpCount = 0;
    g_paccDeniedLogCount = 0;

    PaccCreateDevice(DriverObject);

    DriverObject->MajorFunction[IRP_MJ_CREATE]         = PaccDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_CLOSE]          = PaccDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_DEVICE_CONTROL] = PaccDispatchDeviceControl;
    for (int i = 0; i < IRP_MJ_MAXIMUM_FUNCTION; i++) {
        if (DriverObject->MajorFunction[i] == NULL) DriverObject->MajorFunction[i] = PaccDispatchDefault;
    }
    DriverObject->DriverUnload = PaccUnload;

    status = PaccObHookInitialize();
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] ObRegisterCallbacks failed %x (继续以内存扫描/IOCTL 工作)\n", status);
    }

    g_paccInitialized = TRUE;
    DbgPrint("[PACC] DriverEntry loaded ok\n");
    return STATUS_SUCCESS;
}