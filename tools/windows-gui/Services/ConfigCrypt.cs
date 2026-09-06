using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace PaccManager.Services;

/// <summary>
/// 与 Java 侧 LocalSecureStore 字节级兼容的本地配置加解密（AES-256-GCM）。
/// <para>容器格式：{magic "PACC"(4)}{version:1}{saltLen:int BE}{salt(16)}{ivLen:int BE}{iv(12)}{AES-GCM 密文+128bit tag}；
/// 密钥 = PBKDF2-HmacSHA256(password=salt, 120_000 次, 32B)。值为 "enc:" + Base64(container)。</para>
/// <para>密码只用设备指纹（不绑 pteid）：wss/sig/token 为设备全局，避免加密时 pteid 尚未知的失配。
/// 仅作本机可读加密，防配置被随手拷贝后明文泄漏，非 KMS 级防护。</para>
/// </summary>
public static class ConfigCrypt
{
    private const byte Magic0 = (byte)'P';
    private const byte Magic1 = (byte)'A';
    private const byte Magic2 = (byte)'C';
    private const byte Magic3 = (byte)'C';
    private const int VERSION = 1;
    private const int SaltLen = 16;
    private const int IvLen = 12;
    private const int TagLen = 16;
    private const int Iterations = 120_000;
    private const int KeyBytes = 32;

    /// <summary>落盘前缀，标识该值为加密容器。</summary>
    public const string Prefix = "enc:";

    /// <summary>当前设备指纹（跨 C#/Java 双方一致的规范化形式）。</summary>
    public static string MachineFingerprint() => MachineFingerprintCore(
        "windows",
        RuntimeInformation.ProcessArchitecture switch
        {
            Architecture.X64 => "amd64",
            Architecture.Arm64 => "arm64",
            Architecture.X86 => "x86",
            _ => "unknown",
        },
        Environment.UserName,
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        Environment.MachineName);

    /// <summary>规范化指纹（注入式，便于跨语言锁定测试）。</summary>
    internal static string MachineFingerprintCore(string os, string arch, string user, string home, string host)
    {
        string norm(string s) => s.Replace('/', '\\')
                                  .TrimEnd('\\')
                                  .ToLowerInvariant();
        string raw = $"pacc1|{os}|{arch}|{user}|{norm(home)}|{norm(host)}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(raw))).ToLowerInvariant();
    }

    private static byte[] Derive(string password, byte[] salt)
        => Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, KeyBytes);

    /// <summary>加密为 "enc:" + base64 容器。</summary>
    public static string Encrypt(string plain, string password)
    {
        byte[] pt = Encoding.UTF8.GetBytes(plain ?? string.Empty);
        byte[] salt = RandomNumberGenerator.GetBytes(SaltLen);
        byte[] nonce = RandomNumberGenerator.GetBytes(IvLen);
        byte[] ct = new byte[pt.Length];
        byte[] tag = new byte[TagLen];
        using (var aes = new AesGcm(Derive(password, salt), TagLen))
        {
            aes.Encrypt(nonce, pt, ct, tag);
        }

        using var ms = new MemoryStream(4 + 1 + 4 + 4 + salt.Length + nonce.Length + ct.Length + tag.Length);
        ms.Write(new byte[] { Magic0, Magic1, Magic2, Magic3, VERSION });
        Span<byte> h = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(h, salt.Length);
        ms.Write(h);
        ms.Write(salt);
        BinaryPrimitives.WriteInt32BigEndian(h, nonce.Length);
        ms.Write(h);
        ms.Write(nonce);
        ms.Write(ct);
        ms.Write(tag);
        return Prefix + Convert.ToBase64String(ms.ToArray());
    }

    /// <summary>
    /// 尝试解密 "enc:" 值；非 "enc:" 前缀返回 null（交由调用方按明文处理）；认证失败（机器/口令不符、被篡改）也返回 null。
    /// </summary>
    public static string? TryDecrypt(string? value, string password)
    {
        if (string.IsNullOrEmpty(value) || !value.StartsWith(Prefix, StringComparison.Ordinal))
            return null;

        byte[] blob;
        try { blob = Convert.FromBase64String(value.Substring(Prefix.Length)); }
        catch { return null; }

        if (!TryParse(blob, out var salt, out var nonce, out var cipher))
            return null;
        if (cipher.Length < TagLen)
            return null;

        byte[] ct = cipher[..^TagLen];
        var tag = cipher[^TagLen..];
        var pt = new byte[ct.Length];
        try
        {
            using var aes = new AesGcm(Derive(password, salt), TagLen);
            aes.Decrypt(nonce, ct, tag, pt);
            return Encoding.UTF8.GetString(pt);
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    private static bool TryParse(byte[] blob, out byte[] salt, out byte[] nonce, out byte[] cipher)
    {
        salt = nonce = cipher = Array.Empty<byte>();
        int BASE = 4 + 1;                     // magic + version
        if (blob.Length < BASE + 4 + 4 + 16 + 12 + TagLen) return false;
        if (blob[0] != Magic0 || blob[1] != Magic1 || blob[2] != Magic2 || blob[3] != Magic3) return false;
        if (blob[4] != VERSION) return false;

        int saltLen = BinaryPrimitives.ReadInt32BigEndian(blob.AsSpan(BASE, 4));
        int ivLen = BinaryPrimitives.ReadInt32BigEndian(blob.AsSpan(BASE + 4, 4));
        if (saltLen <= 0 || saltLen > 128 || ivLen <= 0 || ivLen > 64) return false;
        int bodyStart = BASE + 8 + saltLen + ivLen;
        if (blob.Length < bodyStart + TagLen) return false;

        salt = blob[BASE + 8 .. (BASE + 8 + saltLen)];
        nonce = blob[(BASE + 8 + saltLen) .. bodyStart];
        cipher = blob[bodyStart..];
        return true;
    }
}