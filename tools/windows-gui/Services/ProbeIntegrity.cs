using System;
using System.IO;
using System.Security.Cryptography;

namespace PaccManager.Services;

/// <summary>
/// 本地探针产物完整性自校验。
///
/// <para>威胁模型：真正执行检测的是探针 jar，攻击者把它换成打过补丁的版本，检测就静默失效了——
/// 界面照常显示「运行中」。下载环节已有 SHA256 校验（见 <see cref="UpdateChecker.DownloadProbeAsync"/>），
/// 但那是「下载那一刻」的一次性校验；落盘之后被替换，没有任何环节会发现。</para>
///
/// <para>做法：替换成功后把 jar 的摘要封存到磁盘，启动时重新计算并比对。
/// 封存值用设备指纹加密（复用 <see cref="ConfigCrypt"/>），免得明文摘要文件被顺手改掉。</para>
///
/// <para>这不是 KMS 级防护：同一台机器上、以同一用户身份运行的攻击者仍可重新封存。
/// 它挡住的是「改一个文本文件」这种低成本篡改，以及装完之后的静默替换。</para>
/// </summary>
public static class ProbeIntegrity
{
    /// <summary>封存文件相对探针 jar 的后缀，即 &lt;probe&gt;.seal。</summary>
    public const string SealSuffix = ".seal";

    /// <summary>校验结论。</summary>
    public enum State
    {
        /// <summary>摘要一致。</summary>
        Ok,

        /// <summary>摘要不一致：jar 被替换，或封存文件被改。</summary>
        Mismatch,

        /// <summary>探针 jar 不存在（尚未安装）。</summary>
        ProbeMissing,

        /// <summary>没有可用的封存摘要（首次安装，或换了机器/用户导致解不开），无法判定。</summary>
        Unsealed,
    }

    /// <summary>把当前探针 jar 的摘要封存到 <paramref name="sealFile"/>。返回是否写入成功。</summary>
    public static bool Seal(string probeFile, string sealFile)
    {
        try
        {
            if (!File.Exists(probeFile)) return false;
            string sealedValue = ConfigCrypt.Encrypt(HashOf(probeFile), ConfigCrypt.MachineFingerprint());
            File.WriteAllText(sealFile, sealedValue);
            return true;
        }
        catch
        {
            // 封存失败不影响主流程：下次启动会退回 Unsealed，而不是误报篡改
            return false;
        }
    }

    /// <summary>比对探针 jar 与封存摘要。任何异常都归为 <see cref="State.Unsealed"/>，绝不抛给调用方。</summary>
    public static State Verify(string probeFile, string sealFile)
    {
        try
        {
            if (!File.Exists(probeFile)) return State.ProbeMissing;
            if (!File.Exists(sealFile)) return State.Unsealed;

            string? expected = ConfigCrypt.TryDecrypt(
                File.ReadAllText(sealFile).Trim(), ConfigCrypt.MachineFingerprint());
            // 解不开说明封存不是本机/本用户写的（换了设备、换了用户），不能据此判定被篡改
            if (expected is null) return State.Unsealed;

            return string.Equals(expected, HashOf(probeFile), StringComparison.OrdinalIgnoreCase)
                ? State.Ok
                : State.Mismatch;
        }
        catch
        {
            return State.Unsealed;
        }
    }

    private static string HashOf(string path) =>
        Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant();
}
