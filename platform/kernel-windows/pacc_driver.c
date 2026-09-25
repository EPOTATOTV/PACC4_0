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

// 排空某一类别的环形事件。用户侧长度仅作上限参考，最终以容量与输出缓冲为准。
static NTSTATUS
PaccDrainEvents(ULONG eventClass, PIRP Irp, PIO_STACK_LOCATION irpSp, PULONG pInfo)
{
    ULONG outLen = irpSp->Parameters.DeviceIoControl.OutputBufferLength;
    ULONG inLen  = irpSp->Parameters.DeviceIoControl.InputBufferLength;
    PVOID sysBuf = Irp->AssociatedIrp.SystemBuffer;
    ULONG headerSize = (ULONG)sizeof(PACC_EVENT_BATCH);

    if (sysBuf == NULL) return STATUS_INVALID_PARAMETER;
    if (outLen < headerSize + sizeof(PACC_EVENT)) return STATUS_BUFFER_TOO_SMALL;

    ULONG maxCount = (outLen - headerSize) / (ULONG)sizeof(PACC_EVENT);
    if (maxCount > PACC_EVENT_RING_CAPACITY) maxCount = PACC_EVENT_RING_CAPACITY;
    if (maxCount == 0) return STATUS_BUFFER_TOO_SMALL;

    if (inLen >= sizeof(PACC_DRAIN_REQUEST)) {
        PPACC_DRAIN_REQUEST req = (PPACC_DRAIN_REQUEST)sysBuf;
        if (req->MaxCount != 0 && req->MaxCount < maxCount) maxCount = req->MaxCount;
    }

    PPACC_EVENT_BATCH batch  = (PPACC_EVENT_BATCH)sysBuf;
    PPACC_EVENT       events = (PPACC_EVENT)((PUCHAR)sysBuf + headerSize);
    ULONG count = PaccEventDrain(eventClass, events, maxCount);

    PACC_EVENT_STATS stats;
    PaccEventGetStats(&stats);
    batch->Class   = eventClass;
    batch->Count   = count;
    batch->Dropped = stats.Dropped;

    *pInfo = headerSize + count * (ULONG)sizeof(PACC_EVENT);
    return STATUS_SUCCESS;
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

    // ---- 事件排空：进程 / 镜像 / 线程 / 注册表 ----
    case IOCTL_PACC_DRAIN_PROCESS_EVENTS:
        status = PaccDrainEvents(PACC_EVENT_CLASS_PROCESS, Irp, irpSp, &info);
        break;
    case IOCTL_PACC_DRAIN_IMAGE_EVENTS:
        status = PaccDrainEvents(PACC_EVENT_CLASS_IMAGE, Irp, irpSp, &info);
        break;
    case IOCTL_PACC_DRAIN_THREAD_EVENTS:
        status = PaccDrainEvents(PACC_EVENT_CLASS_THREAD, Irp, irpSp, &info);
        break;
    case IOCTL_PACC_DRAIN_REGISTRY_EVENTS:
        status = PaccDrainEvents(PACC_EVENT_CLASS_REGISTRY, Irp, irpSp, &info);
        break;

    // ---- 计数器归零（返回归零后的快照）----
    case IOCTL_PACC_RESET_EVENT_COUNTERS: {
        if (Irp->AssociatedIrp.SystemBuffer == NULL ||
            irpSp->Parameters.DeviceIoControl.OutputBufferLength < sizeof(PACC_EVENT_STATS)) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        PaccEventResetCounters();
        PACC_EVENT_STATS snapshot;
        PaccEventGetStats(&snapshot);
        RtlCopyMemory(Irp->AssociatedIrp.SystemBuffer, &snapshot, sizeof(snapshot));
        info = sizeof(snapshot);
        status = STATUS_SUCCESS;
        break;
    }

    // ---- SSDT 完整性状态 ----
    case IOCTL_PACC_QUERY_SSDT_STATUS: {
        if (Irp->AssociatedIrp.SystemBuffer == NULL ||
            irpSp->Parameters.DeviceIoControl.OutputBufferLength < sizeof(PACC_SSDT_STATUS)) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        PACC_SSDT_STATUS ssdt;
        status = PaccSsdtQuery(&ssdt);
        if (NT_SUCCESS(status)) {
            RtlCopyMemory(Irp->AssociatedIrp.SystemBuffer, &ssdt, sizeof(ssdt));
            info = sizeof(ssdt);
        }
        break;
    }

    // ---- 已加载驱动枚举 + 签名核验（分页）----
    case IOCTL_PACC_QUERY_DRIVER_STATUS: {
        ULONG outLen = irpSp->Parameters.DeviceIoControl.OutputBufferLength;
        ULONG inLen  = irpSp->Parameters.DeviceIoControl.InputBufferLength;
        PVOID sysBuf = Irp->AssociatedIrp.SystemBuffer;
        ULONG headerSize = (ULONG)FIELD_OFFSET(PACC_DRIVER_REPORT, Entries);

        if (sysBuf == NULL || outLen < headerSize + sizeof(PACC_DRIVER_ENTRY)) {
            status = STATUS_BUFFER_TOO_SMALL;
            break;
        }
        if (inLen != 0 && inLen < sizeof(PACC_DRIVER_QUERY)) {
            status = STATUS_INVALID_PARAMETER;   // 输入结构不完整
            break;
        }

        PACC_DRIVER_QUERY query;
        RtlZeroMemory(&query, sizeof(query));
        if (inLen >= sizeof(PACC_DRIVER_QUERY)) {
            RtlCopyMemory(&query, sysBuf, sizeof(query));   // 先取输入，再写输出
        }

        status = PaccDriverScanQuery(&query, (PPACC_DRIVER_REPORT)sysBuf, outLen);
        if (NT_SUCCESS(status)) {
            PPACC_DRIVER_REPORT report = (PPACC_DRIVER_REPORT)sysBuf;
            info = headerSize + report->Returned * (ULONG)sizeof(PACC_DRIVER_ENTRY);
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

    // 按注册的逆序反注册，确保不泄漏任何回调。
    PaccObHookUninitialize();
    PaccRegMonUninitialize();
    PaccImageMonUninitialize();
    PaccProcessMonUninitialize();
    PaccSsdtUninitialize();
    PaccEventUninitialize();

    IoDeleteSymbolicLink(&link);
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

    // 1) 事件环形缓冲是关键资源：分配失败即拒绝加载（fail closed），不留空缓冲。
    status = PaccEventInitialize();
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] DriverEntry abort: event ring init failed %x\n", status);
        return status;
    }

    PaccCreateDevice(DriverObject);
    if (DriverObject->DeviceObject == NULL) {
        DbgPrint("[PACC] DriverEntry abort: device creation failed\n");
        PaccEventUninitialize();
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    DriverObject->MajorFunction[IRP_MJ_CREATE]         = PaccDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_CLOSE]          = PaccDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_DEVICE_CONTROL] = PaccDispatchDeviceControl;
    for (int i = 0; i < IRP_MJ_MAXIMUM_FUNCTION; i++) {
        if (DriverObject->MajorFunction[i] == NULL) DriverObject->MajorFunction[i] = PaccDispatchDefault;
    }
    DriverObject->DriverUnload = PaccUnload;

    // 2) SSDT 快照（最佳努力：定位失败仅记录，不影响其余能力）。
    status = PaccSsdtInitialize();
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] SSDT snapshot unavailable %x\n", status);
    }

    // 3) 内核通知回调：逐个注册，失败记录但不阻断，各自内部保证状态一致。
    status = PaccProcessMonInitialize();
    if (!NT_SUCCESS(status)) DbgPrint("[PACC] process/thread monitor unavailable %x\n", status);

    status = PaccImageMonInitialize();
    if (!NT_SUCCESS(status)) DbgPrint("[PACC] image monitor unavailable %x\n", status);

    status = PaccRegMonInitialize();
    if (!NT_SUCCESS(status)) DbgPrint("[PACC] registry monitor unavailable %x\n", status);

    status = PaccObHookInitialize();
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] ObRegisterCallbacks failed %x (继续以内存扫描/IOCTL 工作)\n", status);
    }

    g_paccInitialized = TRUE;
    DbgPrint("[PACC] DriverEntry loaded ok\n");
    return STATUS_SUCCESS;
}