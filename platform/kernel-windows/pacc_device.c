// pacc_device.c - 设备白名单校验（检测宏键盘 / 可疑 USB HID 输入设备）
// 白名单以 REG_MULTI_SZ 存于 HLKM\...\PACC\DeviceWhitelist，由 PTV 灰度下发。
#include "pacc_common.h"
#include <ntstrsafe.h>

#define PACC_REG_PATH L"System\\CurrentControlSet\\Services\\PaccPtv\\Parameters"
#define PACC_VALUE_WHITELIST L"DeviceWhitelist"

// 简单不区分大小写字符串比较（内核侧）
static BOOLEAN
PaccStrIEqual(PCWSTR a, PCWSTR b)
{
    if (a == NULL || b == NULL) return FALSE;
    while (*a && *b) {
        WCHAR ca = (WCHAR)RtlUpcaseUnicodeChar(*a);
        WCHAR cb = (WCHAR)RtlUpcaseUnicodeChar(*b);
        if (ca != cb) return FALSE;
        a++; b++;
    }
    return (*a == 0 && *b == 0);
}

/*
 * 判断某设备实例 ID 是否在白名单内。
 * devInstanceId 形如 "HID\\VID_XXXX&...\\..."。
 * 生产可从 IRP_MN_QUERY_CAPABILITIES / USB 描述符读取以做设备 DNA 画像。
 */
BOOLEAN
PaccDeviceIsAllowed(PCWSTR devInstanceId, ULONG deviceIdLength)
{
    HANDLE key = NULL;
    NTSTATUS status;
    UNICODE_STRING path = RTL_CONSTANT_STRING(PACC_REG_PATH);
    OBJECT_ATTRIBUTES oa;

    InitializeObjectAttributes(&oa, &path, OBJ_KERNEL_HANDLE | OBJ_CASE_INSENSITIVE, NULL, NULL);
    if (devInstanceId == NULL || deviceIdLength == 0) return FALSE;

    status = ZwOpenKey(&key, KEY_READ, &oa);
    if (!NT_SUCCESS(status)) {
        // 无白名单配置 => 保守放行（避免破坏正常外设），仅记录趋势
        return TRUE;
    }

    // 读取 REG_MULTI_SZ
    UCHAR buf[2048];
    ULONG size = sizeof(buf);
    PKEY_VALUE_PARTIAL_INFORMATION pvi = (PKEY_VALUE_PARTIAL_INFORMATION)buf;
    UNICODE_STRING valueName = RTL_CONSTANT_STRING(PACC_VALUE_WHITELIST);
    status = ZwQueryValueKey(key, &valueName, KeyValuePartialInformation, pvi, sizeof(buf), &size);

    if (NT_SUCCESS(status) && pvi->Type == REG_MULTI_SZ) {
        // 逐条（空字符串为多值分隔）比对
        PWSTR it = (PWSTR)(pvi->Data);
        PWSTR end = (PWSTR)((PUCHAR)pvi->Data + pvi->DataLength);
        while ((PUCHAR)it + sizeof(WCHAR) <= (PUCHAR)end && *it != L'\0') {
            if (PaccStrIEqual(it, devInstanceId)) {
                ZwClose(key);
                return TRUE;
            }
            while ((PUCHAR)it + sizeof(WCHAR) <= (PUCHAR)end && *it != L'\0') it++;
            if ((PUCHAR)it + sizeof(WCHAR) <= (PUCHAR)end) it++; // 跳过分隔 0
        }
    }
    ZwClose(key);
    return FALSE;
}