// pacc_drivenum.c - 已加载内核模块枚举 + 签名核验（Rootkit 检测）
// 枚举：遍历 PsLoadedModuleList（Passive 级按需调用）。
// 签名：读取磁盘映像的 PE 头部，取 Authenticode 证书目录，做启发式主体提取。
// 约束：本路径由 IOCTL 在 PASSIVE_LEVEL 触发，允许文件读取；不在任何内核通知回调中调用。
#include "pacc_common.h"
#include <ntstrsafe.h>

#define PACC_PE_HEADER_READ  0x1000      // 读取 PE 头部字节数
#define PACC_CERT_MAX_READ   0x20000     // 证书表读取上限（128KB）

// 已知易被滥用 / rootkit 常用驱动基名（表精简，生产由服务端下发扩充）。
static const WCHAR *const g_paccBadDriverNames[] = {
    L"titanhide", L"dbk", L"kdmapper", L"winio", L"iqvw64e", L"gdrv",
};
#define PACC_BAD_DRIVER_COUNT \
    (sizeof(g_paccBadDriverNames) / sizeof(g_paccBadDriverNames[0]))

static PCWSTR
PaccDriverBaseName(PCWSTR path)
{
    if (path == NULL) return L"";
    PCWSTR base = path;
    for (PCWSTR p = path; *p != L'\0'; p++) {
        if (*p == L'\\' || *p == L'/') base = p + 1;
    }
    return base;
}

static BOOLEAN
PaccDriverNameIsBad(PCWSTR path)
{
    PCWSTR base = PaccDriverBaseName(path);
    for (ULONG i = 0; i < PACC_BAD_DRIVER_COUNT; i++) {
        if (PaccStrContainsCI(base, g_paccBadDriverNames[i])) return TRUE;
    }
    return FALSE;
}

// 打开并读取文件指定偏移（同步、内核句柄、Passive 级）。
static NTSTATUS
PaccReadFileAt(PCUNICODE_STRING path, ULONG64 offset, PVOID buffer, ULONG length, PULONG pRead)
{
    OBJECT_ATTRIBUTES oa;
    IO_STATUS_BLOCK iosb;
    HANDLE handle = NULL;
    LARGE_INTEGER byteOffset;

    InitializeObjectAttributes(&oa, (PUNICODE_STRING)path,
                               OBJ_KERNEL_HANDLE | OBJ_CASE_INSENSITIVE, NULL, NULL);

    NTSTATUS status = ZwCreateFile(
        &handle,
        FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        &oa, &iosb, NULL, FILE_ATTRIBUTE_NORMAL,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        FILE_OPEN,
        FILE_SYNCHRONOUS_IO_NONALERT | FILE_NON_DIRECTORY_FILE,
        NULL, 0);
    if (!NT_SUCCESS(status)) return status;

    byteOffset.QuadPart = (LONGLONG)offset;
    status = ZwReadFile(handle, NULL, NULL, NULL, &iosb,
                        buffer, length, &byteOffset, NULL);
    if (NT_SUCCESS(status)) {
        if (pRead != NULL) *pRead = (ULONG)iosb.Information;
    }
    ZwClose(handle);
    return status;
}

// 把一个 ASN.1 字符串值转为宽字符（BMPString 大端；其余按 ASCII 近似）。
static ULONG
PaccDerCopyValue(PUCHAR s, ULONG slen, UCHAR tag, PWCHAR dst, ULONG cchDst)
{
    ULONG n = 0;
    if (dst == NULL || cchDst == 0) return 0;
    if (tag == 0x1E) {                       // BMPString
        ULONG chars = slen / 2;
        for (ULONG k = 0; k < chars && n < cchDst - 1; k++) {
            dst[n++] = (WCHAR)((s[k * 2] << 8) | s[k * 2 + 1]);
        }
    } else {
        for (ULONG k = 0; k < slen && n < cchDst - 1; k++) {
            UCHAR c = s[k];
            dst[n++] = (c >= 0x20 && c <= 0x7E) ? (WCHAR)c : L'?';
        }
    }
    dst[n] = L'\0';
    return n;
}

// 在证书表中按 OID 2.5.4.3 (06 03 55 04 03) 启发式提取前两个 commonName。
// 第一个通常为签发者 CA，第二个为签名主体；两者都用于“由谁签名”的判定。
static ULONG
PaccExtractCommonNames(PUCHAR blob, ULONG len,
                       PWCHAR out1, ULONG cch1, PWCHAR out2, ULONG cch2)
{
    ULONG found = 0;
    for (ULONG i = 0; i + 5 < len; i++) {
        if (blob[i] != 0x06 || blob[i + 1] != 0x03 ||
            blob[i + 2] != 0x55 || blob[i + 3] != 0x04 || blob[i + 4] != 0x03) {
            continue;
        }
        ULONG j = i + 5;
        if (j + 1 >= len) break;
        UCHAR tag = blob[j];
        ULONG slen = blob[j + 1];
        if (slen == 0 || (slen & 0x80) != 0 || j + 2 + slen > len) continue;   // 仅处理短长度
        if (tag != 0x0C && tag != 0x13 && tag != 0x16 && tag != 0x14 && tag != 0x1E) continue;

        found++;
        if (found == 1) {
            PaccDerCopyValue(blob + j + 2, slen, tag, out1, cch1);
        } else if (found == 2) {
            PaccDerCopyValue(blob + j + 2, slen, tag, out2, cch2);
            break;
        }
        i = j + 1 + slen;
    }
    return found;
}

// 核验单个映像：填充签名标记与主体名。无法读取时按“可疑”处理（fail-closed）。
static VOID
PaccCheckImageSignature(
    PUNICODE_STRING path,
    PULONG flagsOut,
    PWCHAR signer, ULONG signerCch,
    PWCHAR issuer, ULONG issuerCch)
{
    *flagsOut = 0;
    if (signer != NULL && signerCch > 0) signer[0] = L'\0';
    if (issuer != NULL && issuerCch > 0) issuer[0] = L'\0';
    if (path == NULL || path->Buffer == NULL) {
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;
        return;
    }

    PUCHAR header = (PUCHAR)ExAllocatePool2(POOL_FLAG_NON_PAGED, PACC_PE_HEADER_READ, 'ccap');
    if (header == NULL) {
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;   // 无缓冲即无法核验，保守标记
        return;
    }

    ULONG read = 0;
    NTSTATUS status = PaccReadFileAt(path, 0, header, PACC_PE_HEADER_READ, &read);
    if (!NT_SUCCESS(status) || read < sizeof(IMAGE_DOS_HEADER) + sizeof(IMAGE_NT_HEADERS)) {
        ExFreePoolWithTag(header, 'ccap');
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;
        return;
    }

    PIMAGE_DOS_HEADER dos = (PIMAGE_DOS_HEADER)header;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE ||
        dos->e_lfanew <= 0 ||
        (ULONG)dos->e_lfanew + sizeof(IMAGE_NT_HEADERS) > read) {
        ExFreePoolWithTag(header, 'ccap');
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;
        return;
    }

    PIMAGE_NT_HEADERS nt = (PIMAGE_NT_HEADERS)(header + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) {
        ExFreePoolWithTag(header, 'ccap');
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;
        return;
    }

    // Security 目录的 VirtualAddress 是“文件偏移”（非 RVA）。
    PIMAGE_DATA_DIRECTORY sec = &nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_SECURITY];
    if (sec->VirtualAddress == 0 || sec->Size == 0) {
        ExFreePoolWithTag(header, 'ccap');
        *flagsOut |= (PACC_DRIVER_FLAG_UNSIGNED | PACC_DRIVER_FLAG_SUSPECT);
        return;
    }

    ULONG certLen = sec->Size;
    if (certLen > PACC_CERT_MAX_READ) certLen = PACC_CERT_MAX_READ;

    PUCHAR cert = (PUCHAR)ExAllocatePool2(POOL_FLAG_NON_PAGED, certLen, 'ccap');
    if (cert == NULL) {
        ExFreePoolWithTag(header, 'ccap');
        *flagsOut |= (PACC_DRIVER_FLAG_SIGNED | PACC_DRIVER_FLAG_SUSPECT);  // 有证书表但无法提取主体
        return;
    }

    ULONG certRead = 0;
    status = PaccReadFileAt(path, sec->VirtualAddress, cert, certLen, &certRead);
    ExFreePoolWithTag(header, 'ccap');

    if (!NT_SUCCESS(status) || certRead == 0) {
        ExFreePoolWithTag(cert, 'ccap');
        *flagsOut |= (PACC_DRIVER_FLAG_SIGNED | PACC_DRIVER_FLAG_SUSPECT);
        return;
    }

    ULONG found = PaccExtractCommonNames(cert, certRead, issuer, issuerCch, signer, signerCch);
    ExFreePoolWithTag(cert, 'ccap');

    *flagsOut |= PACC_DRIVER_FLAG_SIGNED;
    if (found == 0) {
        // 已签名但主体无法解析 —— 标可疑，交由用户态复核。
        *flagsOut |= PACC_DRIVER_FLAG_SUSPECT;
        return;
    }

    BOOLEAN microsoft =
        PaccStrContainsCI(signer, L"Microsoft") || PaccStrContainsCI(issuer, L"Microsoft");
    if (microsoft) {
        *flagsOut |= PACC_DRIVER_FLAG_MICROSOFT;
    } else {
        *flagsOut |= (PACC_DRIVER_FLAG_NON_MICROSOFT | PACC_DRIVER_FLAG_SUSPECT);
    }
}

// 枚举 + 分页 + 签名核验。
NTSTATUS
PaccDriverScanQuery(PPACC_DRIVER_QUERY req, PPACC_DRIVER_REPORT out, ULONG outLen)
{
    if (req == NULL || out == NULL) return STATUS_INVALID_PARAMETER;

    ULONG headerSize = (ULONG)FIELD_OFFSET(PACC_DRIVER_REPORT, Entries);
    if (outLen < headerSize + sizeof(PACC_DRIVER_ENTRY)) return STATUS_BUFFER_TOO_SMALL;

    ULONG maxFit = (outLen - headerSize) / (ULONG)sizeof(PACC_DRIVER_ENTRY);
    ULONG want = req->MaxCount;
    if (want == 0 || want > PACC_DRIVER_MAX_PER_CALL) want = PACC_DRIVER_MAX_PER_CALL;
    if (want > maxFit) want = maxFit;

    ULONG total = 0;
    ULONG returned = 0;
    ULONG suspicious = 0;
    ULONG pos = 0;

    if (PsLoadedModuleList != NULL) {
        __try {
            PLIST_ENTRY head = PsLoadedModuleList;
            for (PLIST_ENTRY link = head->Flink; link != head; link = link->Flink, pos++) {
                total = pos + 1;
                if (pos < req->StartIndex) continue;
                if (returned >= want) continue;

                PKLDR_DATA_TABLE_ENTRY mod =
                    CONTAINING_RECORD(link, KLDR_DATA_TABLE_ENTRY, InLoadOrderLinks);
                PPACC_DRIVER_ENTRY ent = &out->Entries[returned];
                RtlZeroMemory(ent, sizeof(*ent));

                PaccCopyUnicode(&mod->FullDllName, ent->ImagePath, PACC_DRIVER_PATH_MAX);
                ent->Base = (ULONG64)(ULONG_PTR)mod->DllBase;
                ent->Size = (ULONG64)mod->SizeOfImage;
                if (ent->ImagePath[0] == L'\0') {
                    PaccCopyUnicode(&mod->BaseDllName, ent->ImagePath, PACC_DRIVER_PATH_MAX);
                }

                PaccCheckImageSignature(&mod->FullDllName, &ent->Flags,
                                        ent->SignerName, PACC_DRIVER_SIGNER_MAX,
                                        ent->IssuerName, PACC_DRIVER_SIGNER_MAX);

                if (PaccDriverNameIsBad(ent->ImagePath)) ent->Flags |= PACC_DRIVER_FLAG_SUSPECT;
                if (ent->Flags & PACC_DRIVER_FLAG_SUSPECT) suspicious++;
                returned++;
            }
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            // 列表遍历异常：返回已收集部分，不做补偿性遍历。
        }
    }

    out->TotalLoaded = total;
    out->Returned    = returned;
    out->NextIndex   = req->StartIndex + returned;
    out->Suspicious  = suspicious;
    return STATUS_SUCCESS;
}