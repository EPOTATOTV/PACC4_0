using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;

namespace PaccManager.Services;

/// <summary>
/// 模块 / 可执行文件的 Authenticode 数字签名校验（文档 §4.2 系统进程层）。
/// 经回环接口由 Java 探针驱动，只做「读文件、验签名、取发布者」，不写文件、不联网。
///
/// <para>用 {@code WinVerifyTrust} 做完整信任链校验：吊销检查关掉（{@code WTD_REVOKE_NONE}），
/// 且只允许命中本地缓存（{@code WTD_CACHE_ONLY_URL_RETRIEVAL}），避免在检测链路上发起联网请求——
/// 探针要的是低延迟与可预测性，不是实时吊销。无签名 / 校验失败一律 {@code Valid=false}。</para>
///
/// <para>发布者：{@code CryptQueryObject} 取出签名者证书上下文，从其 DER 编码重建
/// {@link X509Certificate2}，再取 {@code X509NameType.SimpleName}（即 Subject CN）。
/// 取不到时返回 {@code Publisher=null} 并附 {@code Note="publisher_unavailable"}——
/// 签名结果仍然可用，不因为发布者缺失把整条链路搞炸。</para>
///
/// <para>所有方法绝不抛异常：失败以 {@code Valid=false + Note} 返回。</para>
/// </summary>
public static class DllSignatureVerifier
{
    private const int MaxModules = 4096;
    private const int MaxFiles = 64;

    private static readonly Guid GenericVerifyV2Action = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    /// <summary>单个模块 / 文件的签名结论。</summary>
    public sealed record SignatureInfo(string Path, bool Valid, string? Publisher, string? Note);

    /// <summary>
    /// 校验单个文件的 Authenticode 签名。文件不存在返回 {@code Note="missing"}；
    /// 无签名或校验失败返回 {@code Valid=false}；任何异常都归为校验失败。
    /// </summary>
    public static SignatureInfo Verify(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return new SignatureInfo(path ?? string.Empty, false, null, "missing");

        try
        {
            if (!File.Exists(path))
                return new SignatureInfo(path, false, null, "missing");

            bool valid = WinVerifyTrustFile(path);
            if (!valid)
                return new SignatureInfo(path, false, null, "invalid_signature");

            string? publisher = TryGetPublisher(path);
            return new SignatureInfo(path, true, publisher,
                publisher is null ? "publisher_unavailable" : null);
        }
        catch
        {
            return new SignatureInfo(path, false, null, "verify_error");
        }
    }

    /// <summary>
    /// 校验指定进程已加载的所有模块签名（取第一个同名进程，逐模块 Verify，上限 4096）。
    /// 进程不存在或权限不足时返回空表。
    /// </summary>
    public static IReadOnlyList<SignatureInfo> VerifyModules(string processName)
    {
        var result = new List<SignatureInfo>();
        if (string.IsNullOrWhiteSpace(processName))
            return result;

        Process[] procs;
        try { procs = Process.GetProcessesByName(Path.GetFileNameWithoutExtension(processName)); }
        catch { return result; }

        using Process? target = procs.Length > 0 ? procs[0] : null;
        for (int i = 1; i < procs.Length; i++) procs[i].Dispose();
        if (target == null)
            return result;

        try
        {
            int count = 0;
            foreach (ProcessModule module in target.Modules)
            {
                if (count++ >= MaxModules) break;
                string? file = null;
                try { file = module.FileName; } catch { /* 个别模块取不到路径 */ }
                if (string.IsNullOrEmpty(file)) continue;
                result.Add(Verify(file));
            }
        }
        catch
        {
            // 模块枚举中断：返回已拿到的部分结果
        }
        return result;
    }

    /// <summary>逐文件校验签名：路径去重（忽略大小写）、上限 64，超出截断。</summary>
    public static IReadOnlyList<SignatureInfo> VerifyFiles(IEnumerable<string> paths)
    {
        var result = new List<SignatureInfo>();
        if (paths == null) return result;

        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string path in paths)
        {
            if (result.Count >= MaxFiles) break;
            if (string.IsNullOrWhiteSpace(path)) continue;
            if (!seen.Add(path)) continue;
            result.Add(Verify(path));
        }
        return result;
    }

    // ---------- WinVerifyTrust ----------

    private static bool WinVerifyTrustFile(string path)
    {
        IntPtr filePathPtr = IntPtr.Zero;
        IntPtr fileInfoPtr = IntPtr.Zero;
        IntPtr trustDataPtr = IntPtr.Zero;
        try
        {
            filePathPtr = Marshal.StringToHGlobalUni(path);
            var fileInfo = new WintrustFileInfo
            {
                cbStruct = (uint)Marshal.SizeOf<WintrustFileInfo>(),
                pcwszFilePath = filePathPtr,
                hFile = IntPtr.Zero,
                pgKnownSubject = IntPtr.Zero
            };
            fileInfoPtr = Marshal.AllocHGlobal(Marshal.SizeOf<WintrustFileInfo>());
            Marshal.StructureToPtr(fileInfo, fileInfoPtr, false);

            var trustData = new WintrustData
            {
                cbStruct = (uint)Marshal.SizeOf<WintrustData>(),
                dwUIChoice = WtdUiNone,
                fdwRevocationChecks = WtdRevokeNone,
                dwUnionChoice = WtdChoiceFile,
                pFile = fileInfoPtr,
                dwStateAction = WtdStateActionVerify,
                dwProvFlags = WtdCacheOnlyUrlRetrieval,
                dwUIContext = 0
            };
            trustDataPtr = Marshal.AllocHGlobal(Marshal.SizeOf<WintrustData>());
            Marshal.StructureToPtr(trustData, trustDataPtr, false);

            bool valid = WinVerifyTrust(IntPtr.Zero, GenericVerifyV2Action, trustDataPtr) == 0;

            // 关闭状态数据，避免 WINTRUST 内部状态泄漏
            try
            {
                var closeData = Marshal.PtrToStructure<WintrustData>(trustDataPtr);
                closeData.dwStateAction = WtdStateActionClose;
                Marshal.StructureToPtr(closeData, trustDataPtr, false);
                WinVerifyTrust(IntPtr.Zero, GenericVerifyV2Action, trustDataPtr);
            }
            catch { /* 状态关闭失败不影响校验结论 */ }

            return valid;
        }
        catch
        {
            return false;
        }
        finally
        {
            if (fileInfoPtr != IntPtr.Zero) Marshal.FreeHGlobal(fileInfoPtr);
            if (filePathPtr != IntPtr.Zero) Marshal.FreeHGlobal(filePathPtr);
            if (trustDataPtr != IntPtr.Zero) Marshal.FreeHGlobal(trustDataPtr);
        }
    }

    // ---------- 发布者提取 ----------

    private static string? TryGetPublisher(string path)
    {
        IntPtr store = IntPtr.Zero;
        IntPtr msg = IntPtr.Zero;
        IntPtr certInfo = IntPtr.Zero;
        IntPtr certContext = IntPtr.Zero;
        try
        {
            uint encoding = 0, contentType = 0, formatType = 0;
            bool ok = CryptQueryObject(
                CertQueryObjectFile, path,
                CertQueryContentFlagPkcs7SignedEmbed, CertQueryFormatFlagBinary, 0,
                ref encoding, ref contentType, ref formatType,
                out store, out msg, out _);
            if (!ok || msg == IntPtr.Zero || store == IntPtr.Zero)
                return null;

            uint size = 0;
            if (!CryptMsgGetParam(msg, CmsgSignerCertInfoParam, 0, IntPtr.Zero, ref size) || size == 0)
                return null;

            certInfo = Marshal.AllocHGlobal((int)size);
            if (!CryptMsgGetParam(msg, CmsgSignerCertInfoParam, 0, certInfo, ref size))
                return null;

            certContext = CertFindCertificateInStore(store, X509AsnEncoding | Pkcs7AsnEncoding, 0,
                CertFindSubjectCert, certInfo, IntPtr.Zero);
            if (certContext == IntPtr.Zero)
                return null;

            // 从证书上下文的 DER 编码重建托管证书，避免直接接管原生句柄的所有权
            var context = Marshal.PtrToStructure<CertContext>(certContext);
            if (context.pbCertEncoded == IntPtr.Zero || context.cbCertEncoded == 0)
                return null;

            var raw = new byte[context.cbCertEncoded];
            Marshal.Copy(context.pbCertEncoded, raw, 0, raw.Length);
            using var certificate = new X509Certificate2(raw);
            string name = certificate.GetNameInfo(X509NameType.SimpleName, false);
            return string.IsNullOrWhiteSpace(name) ? null : name;
        }
        catch
        {
            return null;
        }
        finally
        {
            if (certContext != IntPtr.Zero) CertFreeCertificateContext(certContext);
            if (certInfo != IntPtr.Zero) Marshal.FreeHGlobal(certInfo);
            if (msg != IntPtr.Zero) CryptMsgClose(msg);
            if (store != IntPtr.Zero) CertCloseStore(store, 0);
        }
    }

    // ---------- 平台调用 ----------

    private const int WtdUiNone = 2;
    private const int WtdRevokeNone = 0;
    private const int WtdChoiceFile = 1;
    private const int WtdStateActionVerify = 1;
    private const int WtdStateActionClose = 2;
    private const uint WtdCacheOnlyUrlRetrieval = 0x0000_1000;

    private const uint CertQueryObjectFile = 0x0000_0001;
    private const uint CertQueryContentFlagPkcs7SignedEmbed = 0x0000_0400;
    private const uint CertQueryFormatFlagBinary = 0x0000_0002;
    private const int CmsgSignerCertInfoParam = 7;
    private const uint CertFindSubjectCert = 0x000B_0000;
    private const uint X509AsnEncoding = 0x0000_0001;
    private const uint Pkcs7AsnEncoding = 0x0001_0000;

    [StructLayout(LayoutKind.Sequential)]
    private struct WintrustFileInfo
    {
        public uint cbStruct;
        public IntPtr pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WintrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CertContext
    {
        public uint dwCertEncodingType;
        public IntPtr pbCertEncoded;
        public uint cbCertEncoded;
        public IntPtr pCertInfo;
        public IntPtr hCertStore;
    }

    [DllImport("wintrust.dll", ExactSpelling = true)]
    private static extern int WinVerifyTrust(IntPtr hwnd, [MarshalAs(UnmanagedType.LPStruct)] Guid actionId,
        IntPtr wvtData);

    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CryptQueryObject(uint dwObjectType,
        [MarshalAs(UnmanagedType.LPWStr)] string pvObject,
        uint dwExpectedContentTypeFlags, uint dwExpectedFormatTypeFlags, uint dwFlags,
        ref uint pdwMsgAndCertEncodingType, ref uint pdwContentType, ref uint pdwFormatType,
        out IntPtr phCertStore, out IntPtr phMsg, out IntPtr ppvContext);

    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern bool CryptMsgGetParam(IntPtr hCryptMsg, int dwParamType, uint dwIndex,
        IntPtr pvData, ref uint pcbData);

    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern IntPtr CertFindCertificateInStore(IntPtr hCertStore, uint dwCertEncodingType,
        uint dwFindFlags, uint dwFindType, IntPtr pvFindPara, IntPtr pPrevCertContext);

    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern bool CertFreeCertificateContext(IntPtr pCertContext);

    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern bool CertCloseStore(IntPtr hCertStore, uint dwFlags);

    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern bool CryptMsgClose(IntPtr hCryptMsg);
}