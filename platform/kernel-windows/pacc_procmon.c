// pacc_procmon.c - 进程创建 / 线程创建监视（内核通知回调）
// 注册：PsSetCreateProcessNotifyRoutineEx + PsSetCreateThreadNotifyRoutine
// 约束：回调可能运行在提升的 IRQL；仅使用非分页内存，不做任何 I/O / 阻塞等待。
#include "pacc_common.h"

// 可疑进程名单（调试器 / 注入工具 / 已知作弊加载器）。
// 匹配方式：对映像基名做不区分大小写子串匹配。表保持精简，避免误伤正常软件。
static const WCHAR *const g_paccSuspiciousProcessNames[] = {
    L"x64dbg", L"x32dbg", L"ollydbg", L"windbg", L"ida64", L"idaq64",
    L"cheatengine", L"cheat engine", L"processhacker", L"procexp", L"procmon",
    L"xenos", L"extreme injector", L"injector", L"scylla", L"titanhide",
    L"ksdumper", L"pchunter", L"syser", L"wurst", L"impact",
};
#define PACC_SUSPICIOUS_PROCESS_COUNT \
    (sizeof(g_paccSuspiciousProcessNames) / sizeof(g_paccSuspiciousProcessNames[0]))

static BOOLEAN g_paccProcessNotifyRegistered = FALSE;
static BOOLEAN g_paccThreadNotifyRegistered  = FALSE;

// 取路径中的基名（最后一个分隔符之后）。
static PCWSTR
PaccBaseName(PCWSTR path)
{
    if (path == NULL) return L"";
    PCWSTR base = path;
    for (PCWSTR p = path; *p != L'\0'; p++) {
        if (*p == L'\\' || *p == L'/') base = p + 1;
    }
    return base;
}

static BOOLEAN
PaccProcessNameIsSuspicious(PCWSTR path)
{
    PCWSTR base = PaccBaseName(path);
    for (ULONG i = 0; i < PACC_SUSPICIOUS_PROCESS_COUNT; i++) {
        if (PaccStrContainsCI(base, g_paccSuspiciousProcessNames[i])) return TRUE;
    }
    return FALSE;
}

// 进程创建回调：仅在命中可疑名单、或由受保护进程派生时上报（避免全量刷屏）。
VOID
PaccProcessNotifyEx(
    PEPROCESS Process,
    HANDLE ProcessId,
    PPS_CREATE_NOTIFY_INFO CreateInfo)
{
    UNREFERENCED_PARAMETER(Process);

    if (CreateInfo == NULL) return;   // 进程退出事件，忽略
    if (!CreateInfo->FileOpenNameAvailable || CreateInfo->ImageFileName == NULL) {
        // 无可用映像路径（如系统早期进程）——无信息可上报
        return;
    }

    WCHAR detail[PACC_EVENT_DETAIL_MAX];
    PaccCopyUnicode(CreateInfo->ImageFileName, detail, PACC_EVENT_DETAIL_MAX);

    ULONG pid       = (ULONG)(ULONG_PTR)ProcessId;
    ULONG parentPid = (ULONG)(ULONG_PTR)CreateInfo->ParentProcessId;

    ULONG flags = 0;
    if (PaccProcessNameIsSuspicious(detail))    flags |= PACC_EVENT_FLAG_SUSPICIOUS;
    if (PaccIsProtectedPid(parentPid))          flags |= PACC_EVENT_FLAG_FROM_PROTECTED;
    if (flags == 0) return;   // 正常进程不入环

    if (flags & PACC_EVENT_FLAG_SUSPICIOUS) PaccMonitorHitInc(PACC_EVENT_CLASS_PROCESS);

    PaccEventPush(PACC_EVENT_CLASS_PROCESS, flags, pid, parentPid, 0, 0, detail);
}

// 线程创建回调：仅记录受保护进程内部新建线程（远程线程注入指示）。
VOID
PaccThreadNotify(
    HANDLE ProcessId,
    HANDLE ThreadId,
    BOOLEAN Create)
{
    if (!Create) return;

    ULONG pid = (ULONG)(ULONG_PTR)ProcessId;
    if (!PaccIsProtectedPid(pid)) return;

    ULONG tid = (ULONG)(ULONG_PTR)ThreadId;
    PaccMonitorHitInc(PACC_EVENT_CLASS_THREAD);
    PaccEventPush(PACC_EVENT_CLASS_THREAD, PACC_EVENT_FLAG_INTO_PROTECTED,
                  pid, 0, (ULONG64)tid, 0, L"");
}

NTSTATUS
PaccProcessMonInitialize(VOID)
{
    NTSTATUS status = PsSetCreateProcessNotifyRoutineEx(PaccProcessNotifyEx, FALSE);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] PsSetCreateProcessNotifyRoutineEx failed %x\n", status);
        return status;
    }
    g_paccProcessNotifyRegistered = TRUE;

    status = PsSetCreateThreadNotifyRoutine(PaccThreadNotify);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] PsSetCreateThreadNotifyRoutine failed %x\n", status);
        // 回滚已注册的进程回调，保持模块状态一致
        PsRemoveCreateProcessNotifyRoutine((PVOID)PaccProcessNotifyEx);
        g_paccProcessNotifyRegistered = FALSE;
        return status;
    }
    g_paccThreadNotifyRegistered = TRUE;
    return STATUS_SUCCESS;
}

VOID
PaccProcessMonUninitialize(VOID)
{
    if (g_paccThreadNotifyRegistered) {
        PsRemoveCreateThreadNotifyRoutine((PVOID)PaccThreadNotify);
        g_paccThreadNotifyRegistered = FALSE;
    }
    if (g_paccProcessNotifyRegistered) {
        PsRemoveCreateProcessNotifyRoutine((PVOID)PaccProcessNotifyEx);
        g_paccProcessNotifyRegistered = FALSE;
    }
}