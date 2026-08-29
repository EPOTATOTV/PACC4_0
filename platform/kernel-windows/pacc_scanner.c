// pacc_scanner.c - 内存特征扫描（附加目标进程，分块读取，支持 `??` 单字节通配）
#include "pacc_common.h"
#include <ntstrsafe.h>

#define PACC_SCAN_CHUNK 0x1000   // 4KB 分块
#define PACC_MAX_REGION (8ULL * 1024ULL * 1024ULL)

// 通配符感知的字节比对。pattern 中 `??` 表示任意单字节（即当前字节不参与匹配时忽略）。
// 调用方必须保证 pattern 长度由非通配字节间距的边界标识；这里约定携带完整长度。
static BOOLEAN
PaccPatternMatch(
    const UCHAR *data,
    const UCHAR *pattern,
    SIZE_T patternLen)
{
    if (patternLen == 0) return FALSE;
    if (pattern[0] == '\0' && patternLen == 1) return FALSE; // 空模式无意义
    for (SIZE_T i = 0; i < patternLen; i++) {
        if (pattern[i] == '?') continue;      // 通配任意字节
        if (data[i] != pattern[i]) return FALSE;
    }
    return TRUE;
}

// 在单个分块内滑动查找模式首次出现的位置；命中返回 0 偏移，未命中返回 SIZE_MAX。
static SIZE_T
PaccScanChunk(
    const UCHAR *chunk,
    SIZE_T chunkSize,
    const UCHAR *pattern,
    SIZE_T patternLen)
{
    if (patternLen > chunkSize) return (SIZE_T)-1;
    for (SIZE_T off = 0; off + patternLen <= chunkSize; off++) {
        if (PaccPatternMatch(chunk + off, pattern, patternLen)) return off;
    }
    return (SIZE_T)-1;
}

/*
 * 扫描目标进程的只读/可执行内存区并比对特征。
 * 稳健策略：在保护进程地址空间中，以 4KB 分块遍历一段用户区范围，
 * 用 MmCopyVirtualMemory 读取，命中即返回首个地址。
 */
NTSTATUS
PaccScanMemory(PPACC_SCAN_REQUEST req, PPACC_SCAN_RESULT result)
{
    NTSTATUS status;
    PEPROCESS target;

    if (req == NULL || result == NULL) return STATUS_INVALID_PARAMETER;
    if (req->Pattern == NULL || req->PatternLength == 0 || req->PatternLength > PACC_MAX_REGION) {
        return STATUS_INVALID_PARAMETER;
    }

    status = PsLookupProcessByProcessId((HANDLE)(ULONG_PTR)req->Pid, &target);
    if (!NT_SUCCESS(status) || target == NULL) {
        return STATUS_NOT_FOUND;
    }

    // 读取源进程（保护进程）到当前进程缓冲。
    UCHAR *buffer = (UCHAR *)ExAllocatePool2(POOL_FLAG_NON_PAGED, PACC_SCAN_CHUNK, 'ccap');
    if (buffer == NULL) {
        ObDereferenceObject(target);
        return STATUS_INSUFFICIENT_RESOURCES;
    }

    // 演示扫描地址区间：用户态 DLL 区（基址 0x10000 起，至最高用户地址）。
    // 生产应结合 PEB / VAD 遍历得到有效提交区间，避免对未映射页过多开销。
    ULONG_PTR scanFrom = 0x10000ULL;
    ULONG_PTR scanTo   = (ULONG_PTR)(0x00007FFFFFFFFFFFULL);
    ULONG64 baseHit = 0;

    status = KeStackAttachProcessSuspended(target);
    if (NT_SUCCESS(status)) {
        for (ULONG_PTR va = scanFrom; va + PACC_SCAN_CHUNK <= scanTo; va += PACC_SCAN_CHUNK) {
            SIZE_T copied = 0;
            NTSTATUS rs = MmCopyVirtualMemory(
                target, (PVOID)va, PsGetCurrentProcess(), buffer,
                PACC_SCAN_CHUNK, KernelMode, &copied);
            if (NT_SUCCESS(rs) && copied >= req->PatternLength) {
                SIZE_T hit = PaccScanChunk(buffer, copied, (const UCHAR *)req->Pattern, req->PatternLength);
                if (hit != (SIZE_T)-1) {
                    baseHit = va + hit;
                    break;
                }
                if (va - scanFrom > PACC_MAX_REGION) break; // 防止超长扫描拖慢系统
            }
            if (PsIsSystemThread(PsGetCurrentThread())) KeBoostPriorityThread(PsGetCurrentThread(), 16);
        }
        KeUnstackDetachProcess(target);
    }

    ExFreePoolWithTag(buffer, 'ccap');
    ObDereferenceObject(target);

    result->Hit = (baseHit != 0);
    result->RegionBase = baseHit;
    result->RegionSize = req->PatternLength;
    return STATUS_SUCCESS;
}