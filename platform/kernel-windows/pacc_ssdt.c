// pacc_ssdt.c - SSDT（系统服务分派表）完整性校验
// 加载时定位并快照服务表条目；按需重读比对，报告分歧（内联内核 Hook 的典型痕迹）。
// 说明：x64 上 KiServiceTable 的条目是 32 位相对偏移（非函数指针），比对原始 ULONG。
//       本模块只读、不改写任何内核结构，因而与 PatchGuard 兼容。
#include "pacc_common.h"

// x64 KSERVICE_TABLE_DESCRIPTOR 布局（Base/Count/Number 为指针，Limit 为 ULONG 计数）。
typedef struct _PACC_KSERVICE_DESCRIPTOR {
    PVOID Base;     // -> KiServiceTable（PULONG 数组）
    PVOID Count;    // -> KiServiceLimit
    ULONG Limit;    // 服务项数
    PVOID Number;   // -> KiArgumentTable
} PACC_KSERVICE_DESCRIPTOR, *PPACC_KSERVICE_DESCRIPTOR;

static PVOID   g_paccSsdtNtosBase   = NULL;
static SIZE_T  g_paccSsdtNtosSize   = 0;
static PVOID   g_paccSsdtDescriptor = NULL;
static PULONG  g_paccSsdtTable      = NULL;
static ULONG   g_paccSsdtCount      = 0;
static PULONG  g_paccSsdtSnapshot   = NULL;   // 非分页池快照

static BOOLEAN
PaccPtrInImage(PVOID ptr, PVOID base, SIZE_T size)
{
    return ((ULONG_PTR)ptr >= (ULONG_PTR)base) &&
           ((ULONG_PTR)ptr <  (ULONG_PTR)base + size);
}

// 在 ntoskrnl 映像中扫描 KSERVICE_TABLE_DESCRIPTOR 候选。
// 约束足够严苛（三个指针均落在映像内、Limit 合理、Count 指向 Limit、首项为合法偏移），
// 可在无符号表的条件下唯一命中主服务表描述符。
static BOOLEAN
PaccLocateDescriptor(PVOID imageBase, SIZE_T imageSize,
                     PVOID *descOut, PULONG *tableOut, PULONG countOut)
{
    PUCHAR start = (PUCHAR)imageBase;
    PUCHAR end   = start + imageSize;

    for (PUCHAR p = start; p + sizeof(PACC_KSERVICE_DESCRIPTOR) <= end; p += sizeof(PVOID)) {
        PVOID basePtr  = NULL;
        PVOID countPtr = NULL;
        PVOID numPtr   = NULL;
        ULONG limit    = 0;

        __try {
            basePtr  = *(PVOID *)(p + 0);
            countPtr = *(PVOID *)(p + 8);
            limit    = *(ULONG *)(p + 16);
            numPtr   = *(PVOID *)(p + 24);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            continue;
        }

        if (!PaccPtrInImage(basePtr, imageBase, imageSize))  continue;
        if (!PaccPtrInImage(countPtr, imageBase, imageSize)) continue;
        if (!PaccPtrInImage(numPtr, imageBase, imageSize))   continue;
        if (limit < 0x100 || limit > 0x2000) continue;

        PULONG table = (PULONG)basePtr;
        // 服务表整体必须落在映像内
        if ((ULONG_PTR)table + (SIZE_T)limit * sizeof(ULONG) > (ULONG_PTR)imageBase + imageSize) continue;

        __try {
            if (*(PULONG)countPtr != limit) continue;      // Count 指向 Limit
            if (table[0] >= imageSize) continue;           // 首项为合法 RVA
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            continue;
        }

        *descOut  = p;
        *tableOut = table;
        *countOut = limit;
        return TRUE;
    }
    return FALSE;
}

NTSTATUS
PaccSsdtInitialize(VOID)
{
    UNICODE_STRING routine = RTL_CONSTANT_STRING(L"ZwClose");
    PVOID codeAddr = MmGetSystemRoutineAddress(&routine);
    if (codeAddr == NULL) {
        DbgPrint("[PACC] SSDT: MmGetSystemRoutineAddress(ZwClose) failed\n");
        return STATUS_NOT_SUPPORTED;
    }

    PVOID imageBase = NULL;
    RtlPcToFileHeader(codeAddr, &imageBase);
    if (imageBase == NULL) {
        DbgPrint("[PACC] SSDT: RtlPcToFileHeader failed\n");
        return STATUS_NOT_SUPPORTED;
    }

    PIMAGE_NT_HEADERS nt = RtlImageNtHeader(imageBase);
    if (nt == NULL) {
        DbgPrint("[PACC] SSDT: RtlImageNtHeader failed\n");
        return STATUS_NOT_SUPPORTED;
    }
    SIZE_T imageSize = nt->OptionalHeader.SizeOfImage;

    PVOID  descriptor = NULL;
    PULONG table = NULL;
    ULONG  count = 0;
    if (!PaccLocateDescriptor(imageBase, imageSize, &descriptor, &table, &count)) {
        DbgPrint("[PACC] SSDT: service descriptor not found\n");
        return STATUS_NOT_SUPPORTED;
    }

    PULONG snapshot = (PULONG)ExAllocatePool2(POOL_FLAG_NON_PAGED,
                                              (SIZE_T)count * sizeof(ULONG), 'ccap');
    if (snapshot == NULL) {
        // 分配失败即上报，绝不带着空快照继续。
        DbgPrint("[PACC] SSDT: snapshot allocation failed\n");
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    __try {
        RtlCopyMemory(snapshot, table, (SIZE_T)count * sizeof(ULONG));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        ExFreePoolWithTag(snapshot, 'ccap');
        DbgPrint("[PACC] SSDT: snapshot read faulted\n");
        return STATUS_UNSUCCESSFUL;
    }

    g_paccSsdtNtosBase   = imageBase;
    g_paccSsdtNtosSize   = imageSize;
    g_paccSsdtDescriptor = descriptor;
    g_paccSsdtTable      = table;
    g_paccSsdtCount      = count;
    g_paccSsdtSnapshot   = snapshot;

    DbgPrint("[PACC] SSDT snapshot ok: %lu entries @ %p\n", count, table);
    return STATUS_SUCCESS;
}

VOID
PaccSsdtUninitialize(VOID)
{
    if (g_paccSsdtSnapshot != NULL) {
        ExFreePoolWithTag(g_paccSsdtSnapshot, 'ccap');
        g_paccSsdtSnapshot = NULL;
    }
    g_paccSsdtDescriptor = NULL;
    g_paccSsdtTable      = NULL;
    g_paccSsdtCount      = 0;
    g_paccSsdtNtosBase   = NULL;
    g_paccSsdtNtosSize   = 0;
}

NTSTATUS
PaccSsdtQuery(PPACC_SSDT_STATUS out)
{
    if (out == NULL) return STATUS_INVALID_PARAMETER;
    RtlZeroMemory(out, sizeof(*out));
    out->FirstDivergentIndex = 0xFFFFFFFF;

    out->NtoskrnlBase = (ULONG64)(ULONG_PTR)g_paccSsdtNtosBase;
    out->NtoskrnlSize = (ULONG64)g_paccSsdtNtosSize;
    out->DescriptorVa = (ULONG64)(ULONG_PTR)g_paccSsdtDescriptor;
    out->TableVa      = (ULONG64)(ULONG_PTR)g_paccSsdtTable;
    out->SnapshotVa   = (ULONG64)(ULONG_PTR)g_paccSsdtSnapshot;

    if (g_paccSsdtTable == NULL || g_paccSsdtSnapshot == NULL || g_paccSsdtCount == 0) {
        out->Supported = FALSE;
        return STATUS_SUCCESS;
    }

    out->Supported  = TRUE;
    out->EntryCount = g_paccSsdtCount;

    ULONG diverged = 0;
    __try {
        for (ULONG i = 0; i < g_paccSsdtCount; i++) {
            ULONG cur  = g_paccSsdtTable[i];
            ULONG snap = g_paccSsdtSnapshot[i];
            BOOLEAN bad = (cur != snap) || (cur >= g_paccSsdtNtosSize);
            if (bad) {
                diverged++;
                if (out->FirstDivergentIndex == 0xFFFFFFFF) {
                    out->FirstDivergentIndex = i;
                    out->FirstSnapshotValue = snap;
                    out->FirstCurrentValue  = cur;
                }
            }
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // 服务表不可读 —— 视为不支持，避免误报。
        out->Supported = FALSE;
        return STATUS_SUCCESS;
    }

    out->DivergenceCount = diverged;
    return STATUS_SUCCESS;
}