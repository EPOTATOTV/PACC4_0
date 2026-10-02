using System.Buffers.Binary;
using System.Reflection.PortableExecutable;
using System.Text;

namespace PaccManager.Pco;

/// <summary>
/// 元数据 #Strings 堆的定位与线性索引。
///
/// <para>线性扫一遍得到「每个真实字符串的起点与字节长度」，用来回答两个问题：某个偏移是不是
/// 一个字符串的起点（不是的话说明它是被后缀共享捡来的尾巴），以及它占了多少字节。</para>
/// </summary>
internal sealed class StringHeapMap
{
    private readonly List<(int Start, int Length)> _ranges = [];

    /// <summary>#Strings 堆在文件里的起始偏移。</summary>
    public int Start { get; }

    /// <summary>#Strings 堆的字节长度（含结尾 NUL）。</summary>
    public int Size { get; }

    /// <summary>该文件偏移是否落在本堆内。用来断言「改写只动了名字堆」。</summary>
    public bool ContainsFileOffset(int fileOffset) => fileOffset >= Start && fileOffset < Start + Size;

    public static StringHeapMap Locate(byte[] bytes, PEHeaders headers)
    {
        CorHeader cor = headers.CorHeader
            ?? throw new InvalidDataException("不是托管程序集：缺少 CLI 头");
        int root = RvaToOffset(headers, cor.MetadataDirectory.RelativeVirtualAddress);
        if (root <= 0 || root + 16 > bytes.Length)
        {
            throw new InvalidDataException("元数据根偏移非法");
        }
        if (BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(root, 4)) != 0x424A5342)
        {
            throw new InvalidDataException("元数据签名不是 BSJB");
        }

        int p = root + 4 + 2 + 2 + 4;
        uint versionLength = BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(p, 4));
        p += 4 + (int)versionLength;
        p += 2; // flags
        int streams = BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(p, 2));
        p += 2;

        for (int i = 0; i < streams; i++)
        {
            uint offset = BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(p, 4));
            uint size = BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(p + 4, 4));
            p += 8;
            int nameStart = p;
            while (bytes[p] != 0)
            {
                p++;
            }
            string name = Encoding.ASCII.GetString(bytes, nameStart, p - nameStart);
            p++;
            while ((p - nameStart) % 4 != 0)
            {
                p++;
            }
            if (name == "#Strings")
            {
                return new StringHeapMap(bytes, root + (int)offset, (int)size);
            }
        }
        throw new InvalidDataException("元数据里没有 #Strings 堆");
    }

    /// <summary>RVA 转文件偏移：直接查节表，不依赖 System.Reflection.Metadata 的版本差异。</summary>
    private static int RvaToOffset(PEHeaders headers, int rva)
    {
        foreach (SectionHeader section in headers.SectionHeaders)
        {
            if (rva >= section.VirtualAddress && rva < section.VirtualAddress + section.SizeOfRawData)
            {
                return section.PointerToRawData + (rva - section.VirtualAddress);
            }
        }
        return -1;
    }

    private StringHeapMap(byte[] bytes, int start, int size)
    {
        Start = start;
        Size = size;
        // 记录的是堆内相对偏移（与 MetadataTokens.GetHeapOffset 同一坐标系），不是文件偏移。
        int p = start + 1; // 偏移 0 固定是空串
        int end = start + size;
        while (p < end)
        {
            if (bytes[p] == 0)
            {
                p++;
                continue;
            }
            int from = p;
            while (p < end && bytes[p] != 0)
            {
                p++;
            }
            _ranges.Add((from - start, p - from));
            if (p < end)
            {
                p++;
            }
        }
    }

    /// <summary>该偏移若是某个字符串的起点，返回它的字节长度（不含结尾 NUL）；否则返回 -1（后缀共享或落在填充里）。</summary>
    public int DeclaredLength(int offset)
    {
        int lo = 0;
        int hi = _ranges.Count - 1;
        int found = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) / 2;
            if (_ranges[mid].Start <= offset)
            {
                found = mid;
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        if (found < 0 || _ranges[found].Start != offset)
        {
            return -1;
        }
        return _ranges[found].Length;
    }

    /// <summary>[from, to) 之间是否存在别的名字偏移。有的话说明这段范围内被后缀共享，不能改。</summary>
    public static bool HasOffsetBetween(int[] sortedOffsets, int from, int to)
    {
        int lo = 0;
        int hi = sortedOffsets.Length - 1;
        int found = sortedOffsets.Length;
        while (lo <= hi)
        {
            int mid = (lo + hi) / 2;
            if (sortedOffsets[mid] >= from)
            {
                found = mid;
                hi = mid - 1;
            }
            else
            {
                lo = mid + 1;
            }
        }
        return found < sortedOffsets.Length && sortedOffsets[found] < to;
    }
}