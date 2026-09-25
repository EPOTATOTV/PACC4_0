// pacc_imagemon.c - 镜像（DLL / 驱动）加载监视
// 注册：PsSetLoadImageNotifyRoutine
// 约束：回调运行在 PASSIVE/APC；仅非分页内存 + 无 I/O。签名信息取自 IMAGE_INFO 位域。
#include "pacc_common.h"

// 已知恶意 / 作弊镜像基名前缀（表保持精简，生产由服务端下发扩充）。
static const WCHAR *const g_paccBadImagePrefixes[] = {
    L"horion", L"borion", L"axon", L"wurst", L"impact",
    L"titanhide", L"kdmapper", L"reflective",
};
#define PACC_BAD_IMAGE_COUNT \
    (sizeof(g_paccBadImagePrefixes) / sizeof(g_paccBadImagePrefixes[0]))

// SE_SIGNING_LEVEL_*（IMAGE_INFO.ImageSignatureLevel）
#define PACC_SIGN_LEVEL_UNCHECKED   0
#define PACC_SIGN_LEVEL_UNSIGNED    1
// SE_IMAGE_SIGNATURE_TYPE_*（IMAGE_INFO.ImageSignatureType）
#define PACC_SIGN_TYPE_NONE         0

static BOOLEAN g_paccImageNotifyRegistered = FALSE;

static PCWSTR
PaccImageBaseName(PCWSTR path)
{
    if (path == NULL) return L"";
    PCWSTR base = path;
    for (PCWSTR p = path; *p != L'\0'; p++) {
        if (*p == L'\\' || *p == L'/') base = p + 1;
    }
    return base;
}

static BOOLEAN
PaccImageNameIsBad(PCWSTR path)
{
    PCWSTR base = PaccImageBaseName(path);
    for (ULONG i = 0; i < PACC_BAD_IMAGE_COUNT; i++) {
        PCWSTR needle = g_paccBadImagePrefixes[i];
        PCWSTR h = base;
        PCWSTR n = needle;
        while (*h != L'\0' && *n != L'\0') {
            WCHAR ch = *h;
            WCHAR cn = *n;
            if (ch >= L'a' && ch <= L'z') ch = (WCHAR)(ch - (L'a' - L'A'));
            if (cn >= L'a' && cn <= L'z') cn = (WCHAR)(cn - (L'a' - L'A'));
            if (ch != cn) break;
            h++; n++;
        }
        if (*n == L'\0') return TRUE;   // 基名以该前缀开头
    }
    return FALSE;
}

static BOOLEAN
PaccImagePathInTempDir(PCWSTR path)
{
    if (path == NULL) return FALSE;
    if (PaccStrContainsCI(path, L"\\Temp\\"))            return TRUE;
    if (PaccStrContainsCI(path, L"\\AppData\\Local\\Temp")) return TRUE;
    if (PaccStrContainsCI(path, L"\\Windows\\Temp\\"))    return TRUE;
    return FALSE;
}

// 镜像加载回调：仅在可疑时入环（未签名 / 临时目录 / 恶意前缀 / 注入保护进程 / 内核镜像）。
VOID
PaccImageNotify(
    PUNICODE_STRING FullImageName,
    HANDLE ProcessId,
    PIMAGE_INFO ImageInfo)
{
    if (FullImageName == NULL) return;

    WCHAR detail[PACC_EVENT_DETAIL_MAX];
    PaccCopyUnicode(FullImageName, detail, PACC_EVENT_DETAIL_MAX);
    if (detail[0] == L'\0') return;

    ULONG pid = (ULONG)(ULONG_PTR)ProcessId;   // 0 == 内核态镜像
    ULONG flags = 0;

    if (ImageInfo != NULL) {
        if (ImageInfo->ImageSignatureType == PACC_SIGN_TYPE_NONE ||
            ImageInfo->ImageSignatureLevel <= PACC_SIGN_LEVEL_UNSIGNED) {
            flags |= PACC_EVENT_FLAG_UNSIGNED;
        }
        if (ImageInfo->SystemModeImage && pid == 0) {
            flags |= PACC_EVENT_FLAG_KERNEL_IMAGE;
        }
    }
    if (PaccImagePathInTempDir(detail)) flags |= PACC_EVENT_FLAG_TEMP_DIR;
    if (PaccImageNameIsBad(detail))     flags |= PACC_EVENT_FLAG_KNOWN_BAD;
    if (pid != 0 && PaccIsProtectedPid(pid)) flags |= PACC_EVENT_FLAG_INTO_PROTECTED;

    if (flags == 0) return;   // 正常签名镜像不入环

    if (flags & (PACC_EVENT_FLAG_UNSIGNED | PACC_EVENT_FLAG_KNOWN_BAD |
                 PACC_EVENT_FLAG_TEMP_DIR | PACC_EVENT_FLAG_INTO_PROTECTED)) {
        PaccMonitorHitInc(PACC_EVENT_CLASS_IMAGE);
    }
    PaccEventPush(PACC_EVENT_CLASS_IMAGE, flags, pid, 0, 0, 0, detail);
}

NTSTATUS
PaccImageMonInitialize(VOID)
{
    NTSTATUS status = PsSetLoadImageNotifyRoutine(PaccImageNotify);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] PsSetLoadImageNotifyRoutine failed %x\n", status);
        return status;
    }
    g_paccImageNotifyRegistered = TRUE;
    return STATUS_SUCCESS;
}

VOID
PaccImageMonUninitialize(VOID)
{
    if (g_paccImageNotifyRegistered) {
        PsRemoveLoadImageNotifyRoutine((PVOID)PaccImageNotify);
        g_paccImageNotifyRegistered = FALSE;
    }
}