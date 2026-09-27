// pacc_regmon.c - 注册表监视（CmRegisterCallback）
// 重点盯防 Image File Execution Options（调试器劫持）与 AppInit_DLLs（全局注入）。
// 约束：回调运行在 PASSIVE/APC；仅做内存检查，不做任何注册表 I/O，不阻塞。
#include "pacc_common.h"
#include <ntstrsafe.h>

// 大小写不敏感的路径标记（不含首尾反斜杠，便于子串匹配）。
#define PACC_IFEO_MARKER      L"\\Image File Execution Options" 
#define PACC_APPINIT_VALUE    L"AppInit_DLLs"

static LARGE_INTEGER g_paccRegCookie;
static BOOLEAN       g_paccRegRegistered = FALSE;

// 通过 CmCallbackGetKeyObjectIDEx 解析注册表键对象的完整路径（非 I/O，仅内存查询）。
static BOOLEAN
PaccGetKeyPath(PVOID keyObject, PWCHAR dst, ULONG cchDst)
{
    if (dst == NULL || cchDst == 0) return FALSE;
    dst[0] = L'\0';
    if (keyObject == NULL || !g_paccRegRegistered) return FALSE;

    PCUNICODE_STRING name = NULL;
    ULONG_PTR objectId = 0;
    NTSTATUS status = STATUS_UNSUCCESSFUL;
    __try {
        status = CmCallbackGetKeyObjectIDEx(&g_paccRegCookie, keyObject, &objectId, &name, 0);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        status = STATUS_UNSUCCESSFUL;
    }
    if (!NT_SUCCESS(status) || name == NULL) return FALSE;

    PaccCopyUnicode(name, dst, cchDst);
    CmCallbackReleaseKeyObjectIDEx(name);
    return (dst[0] != L'\0');
}

static BOOLEAN
PaccIsAppInitValue(PCUNICODE_STRING valueName)
{
    if (valueName == NULL || valueName->Buffer == NULL) return FALSE;
    if (valueName->Length / sizeof(WCHAR) != 12) return FALSE;   // "AppInit_DLLs"
    WCHAR buf[16];
    PaccCopyUnicode(valueName, buf, ARRAYSIZE(buf));
    return PaccStrEqualCI(buf, PACC_APPINIT_VALUE);
}

static BOOLEAN
PaccIsDebuggerValue(PCUNICODE_STRING valueName)
{
    if (valueName == NULL || valueName->Buffer == NULL) return FALSE;
    if (valueName->Length / sizeof(WCHAR) != 8) return FALSE;    // "Debugger"
    WCHAR buf[12];
    PaccCopyUnicode(valueName, buf, ARRAYSIZE(buf));
    return PaccStrEqualCI(buf, L"Debugger");
}

// 把注册表值数据（限字符串类型）安全地追加到 detail 尾部。
static VOID
PaccAppendValueData(PREG_SET_VALUE_KEY_INFORMATION info, PWCHAR dst, ULONG cchDst)
{
    SIZE_T used = 0;
    if (!NT_SUCCESS(RtlStringCchLengthW(dst, cchDst, &used))) return;
    ULONG avail = cchDst - (ULONG)used - 1;
    if (avail < 4) return;
    if ((info->Type != REG_SZ && info->Type != REG_EXPAND_SZ) ||
        info->Data == NULL || info->DataSize < sizeof(WCHAR) || info->DataSize > 2048) {
        return;
    }

    ULONG chars = info->DataSize / sizeof(WCHAR);
    if (chars >= avail) chars = avail;   // 预留分隔符 + 终止符
    __try {
        RtlStringCchCatNW(dst, cchDst, L"=", 1);
        RtlStringCchCatNW(dst, cchDst, (PCWSTR)info->Data, chars);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // 数据不可读则保持既有内容
    }
}

NTSTATUS
PaccRegistryCallback(
    PVOID CallbackContext,
    PVOID Argument1,
    PVOID Argument2)
{
    UNREFERENCED_PARAMETER(CallbackContext);

    REG_NOTIFY_CLASS notifyClass = (REG_NOTIFY_CLASS)(ULONG_PTR)Argument1;

    switch (notifyClass) {
    case RegNtPreCreateKeyEx: {
        PREG_CREATE_KEY_INFORMATION info = (PREG_CREATE_KEY_INFORMATION)Argument2;
        if (info == NULL || info->CompleteName == NULL) break;
        WCHAR detail[PACC_EVENT_DETAIL_MAX];
        PaccCopyUnicode(info->CompleteName, detail, PACC_EVENT_DETAIL_MAX);
        if (PaccStrContainsCI(detail, PACC_IFEO_MARKER)) {
            PaccMonitorHitInc(PACC_EVENT_CLASS_REGISTRY);
            PaccEventPush(PACC_EVENT_CLASS_REGISTRY, PACC_EVENT_FLAG_IFEO,
                          0, 0, 0, 0, detail);
        }
        break;
    }

    case RegNtPreSetValueKey: {
        PREG_SET_VALUE_KEY_INFORMATION info = (PREG_SET_VALUE_KEY_INFORMATION)Argument2;
        if (info == NULL || info->ValueName == NULL) break;

        if (PaccIsAppInitValue(info->ValueName)) {
            WCHAR detail[PACC_EVENT_DETAIL_MAX];
            RtlStringCchCopyW(detail, ARRAYSIZE(detail), PACC_APPINIT_VALUE);
            PaccAppendValueData(info, detail, ARRAYSIZE(detail));
            PaccMonitorHitInc(PACC_EVENT_CLASS_REGISTRY);
            PaccEventPush(PACC_EVENT_CLASS_REGISTRY, PACC_EVENT_FLAG_APPINIT,
                          0, 0, (ULONG64)info->Type, (ULONG64)info->DataSize, detail);
        } else if (PaccIsDebuggerValue(info->ValueName)) {
            // "Debugger" 值只有在 IFEO 键下才是劫持向量。
            WCHAR keyPath[PACC_EVENT_DETAIL_MAX];
            if (PaccGetKeyPath(info->Object, keyPath, ARRAYSIZE(keyPath)) &&
                PaccStrContainsCI(keyPath, PACC_IFEO_MARKER)) {
                WCHAR detail[PACC_EVENT_DETAIL_MAX];
                RtlStringCchCopyW(detail, ARRAYSIZE(detail), keyPath);
                PaccAppendValueData(info, detail, ARRAYSIZE(detail));
                PaccMonitorHitInc(PACC_EVENT_CLASS_REGISTRY);
                PaccEventPush(PACC_EVENT_CLASS_REGISTRY, PACC_EVENT_FLAG_IFEO,
                              0, 0, 0, 0, detail);
            }
        }
        break;
    }

    default:
        break;
    }

    // 记录优先策略：仅观察上报，不阻断注册表操作。
    return STATUS_SUCCESS;
}

NTSTATUS
PaccRegMonInitialize(VOID)
{
    NTSTATUS status = CmRegisterCallback(PaccRegistryCallback, NULL, &g_paccRegCookie);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] CmRegisterCallback failed %x\n", status);
        return status;
    }
    g_paccRegRegistered = TRUE;
    return STATUS_SUCCESS;
}

VOID
PaccRegMonUninitialize(VOID)
{
    if (g_paccRegRegistered) {
        CmUnRegisterCallback(g_paccRegCookie);
        g_paccRegRegistered = FALSE;
    }
}