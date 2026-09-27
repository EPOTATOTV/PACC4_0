// pacc_obhook.c - 对象回调：拦截对受保护进程的越权访问（NtOpenProcess/DuplicateHandle）
#include "pacc_common.h"

// 授权进程白名单（PTV 查端/管理员进程），空表默认仅记录不拦截。
#define PACC_MAX_AUTH_PID 16
static ULONG g_paccAuthorizedPids[PACC_MAX_AUTH_PID];

// ObRegisterCallbacks 注册句柄；卸载时必须反注册，否则驱动无法卸载。
static PVOID g_paccObRegistration = NULL;

// 被禁用的 Process 访问权限位掩码（检测/取证工具常尝试拿 FULL_CONTROL）
#define PACC_FORBIDDEN_MASK \
    (PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_VM_OPERATION | PROCESS_SUSPEND_RESUME \
     | PROCESS_QUERY_INFORMATION | PROCESS_CREATE_THREAD)

static BOOLEAN
PaccIsAuthorized(ULONG requesterPid)
{
    for (ULONG i = 0; i < PACC_MAX_AUTH_PID; i++) {
        if (g_paccAuthorizedPids[i] != 0 && g_paccAuthorizedPids[i] == requesterPid) return TRUE;
    }
    return FALSE;
}

// 预操作：若非授权进程尝试对受保护进程获取高危访问权限，记录并（默认）拒绝。
OB_PREOP_CALLBACK_STATUS
PaccObPreOperation(
    PVOID RegistrationContext,
    POB_PRE_OPERATION_INFORMATION Info)
{
    // 仅处理句柄创建/复制场景
    if (Info->Operation != OB_OPERATION_HANDLE_CREATE &&
        Info->Operation != OB_OPERATION_HANDLE_DUPLICATE) {
        return OB_PREOP_SUCCESS;
    }

    // 目标对象的进程 PID
    PEPROCESS target = (PEPROCESS)(Info->Object);
    ULONG targetPid = (ULONG)(ULONG_PTR)PsGetProcessId(target);
    if (!PaccIsProtectedPid(targetPid)) return OB_PREOP_SUCCESS;

    ULONG requesterPid = (ULONG)(ULONG_PTR)PsGetCurrentProcessId();
    if (PaccIsAuthorized(requesterPid)) return OB_PREOP_SUCCESS;

    POB_OPERATION_INFORMATION pre = &Info->Parameters.CreateHandleInformation;
    ACCESS_MASK granted = pre->DesiredAccess;

    if (granted & PACC_FORBIDDEN_MASK) {
        if (pre->DesiredAccess & (PROCESS_VM_WRITE | PROCESS_VM_OPERATION | PROCESS_CREATE_THREAD | PROCESS_SUSPEND_RESUME)) {
            // 尝试注入/写内存 => 拦截并计数
            InterlockedIncrement64((volatile LONG64 *)&g_paccBlockedOpCount);
            pre->DesiredAccess &= ~(PACC_FORBIDDEN_MASK);
            DbgPrint("[PACC] block op: requester=%lu -> target=%lu access=%lx\n",
                     requesterPid, targetPid, (unsigned long)granted);
        } else {
            // 只读探查：记录但放行，交由应用层评估
            InterlockedIncrement64((volatile LONG64 *)&g_paccDeniedLogCount);
        }
    }
    return OB_PREOP_SUCCESS;
}

NTSTATUS
PaccObHookInitialize(VOID)
{
    OB_CALLBACK_REGISTRATION obReg = {0};
    OB_OPERATION_REGISTRATION op[1];
    UNICODE_STRING altitude = RTL_CONSTANT_STRING(L"328001");
    op[0].ObjectType = PsProcessType;
    op[0].Operations = OB_OPERATION_HANDLE_CREATE | OB_OPERATION_HANDLE_DUPLICATE;
    op[0].PreOperation = PaccObPreOperation;
    op[0].PostOperation = NULL;

    obReg.Version = OB_FLT_REGISTRATION_VERSION;
    obReg.OperationRegistrationCount = 1;
    obReg.Altitude = altitude;
    obReg.OperationRegistration = op;

    PVOID registration = NULL;
    NTSTATUS status = ObRegisterCallbacks(&obReg, &registration);
    if (NT_SUCCESS(status)) {
        g_paccObRegistration = registration;
    }
    return status;
}

VOID
PaccObHookUninitialize(VOID)
{
    if (g_paccObRegistration != NULL) {
        ObUnRegisterCallbacks(g_paccObRegistration);
        g_paccObRegistration = NULL;
    }
}