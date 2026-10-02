using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Text;

namespace PaccManager.Pco;

/// <summary>
/// PCO 核心（重命名阶段）：就地改写程序集元数据 #Strings 堆里的名字内容。
///
/// <para>手法与 Java 侧的 POB 一致——<b>只动名字内容，不动任何下标</b>。新名一律不长于原名，
/// 不足的部分用 NUL 补齐，字符串堆里的偏移因此保持不变：TypeDef / TypeRef / Field / Method
/// / MemberRef / ImplMap 这些表一行都不用重写，IL、BAML、资源全部原样。</para>
///
/// <para>难点在于名字字符串是按内容去重的：同一个偏移可能被多个实体引用（自研类型与外部
/// TypeRef、ImplMap 的 ImportName、模块引用名……）。所以每个偏移先统计「引用它的所有实体」，
/// 只要其中有一个不能改，整个偏移就放弃。</para>
///
/// <para>更麻烦的是 Roslyn 写入元数据时还会做<b>后缀合并</b>：<c>InheritedFromUniqueProcessId</c>
/// 的尾巴被复用成了 <c>UniqueProcessId</c> 这个名字，两者共用同一段字节。这类偏移要么本身不是
/// 字符串起点，要么被更长的名字包住，就地改写会连带改坏另一个名字，一律跳过——
/// 代价是这部分名字改不掉（实测约两成），彻底解决需要重建整个 #Strings 堆与元数据表。</para>
///
/// <para>能力边界：不做字符串加密、不做控制流平坦化。这两项都要往程序集里新增类型与方法，
/// 那就得真的重写元数据表，与「原地改名」是两套机制，留到下一阶段（设计文档第八节的渐进式）。</para>
/// </summary>
internal sealed class AssemblyRenamer
{
    private readonly PcoRules _rules;
    private readonly Dictionary<int, string> _applied = [];

    public AssemblyRenamer(PcoRules rules) => _rules = rules;

    /// <summary>改名对照表内容，供构建脚本留档（绝不随产物发布）。</summary>
    public string Mapping { get; private set; } = "";

    public int RenamedTypes { get; private set; }

    public int RenamedMembers { get; private set; }

    public void Run(string inputPath, string outputPath, string? mappingPath)
    {
        byte[] bytes = File.ReadAllBytes(inputPath);
        var image = ImmutableCollectionsMarshal.AsImmutableArray(bytes);

        List<TypeNode> types;
        var wanted = new HashSet<int>();
        var blocked = new HashSet<int>();
        var memberLines = new List<MemberLine>();
        StringHeapMap strings;

        // PEReader 是惰性读取：读到一半改字节会读到脏数据，所有分析都在这个块里做完。
        using (var pe = new PEReader(image))
        {
            MetadataReader md = pe.GetMetadataReader();
            strings = StringHeapMap.Locate(bytes, pe.PEHeaders);
            types = BuildTypes(md);
            HashSet<string> selfKeys = types.Select(t => t.FullName).ToHashSet(StringComparer.Ordinal);

            foreach (TypeNode node in types)
            {
                CollectType(md, node, wanted, blocked, memberLines);
            }
            CollectForeignNames(md, selfKeys, blocked);
        }

        int[] offsets = wanted.Concat(blocked).Order().ToArray();
        var assign = new Dictionary<int, string>();
        int counter = 0;
        foreach (int offset in offsets.Where(wanted.Contains))
        {
            assign[offset] = ShortName(counter++);
        }

        foreach (int offset in offsets)
        {
            if (!wanted.Contains(offset) || blocked.Contains(offset))
            {
                continue;
            }
            int length = strings.DeclaredLength(offset);
            if (length <= 0 || StringHeapMap.HasOffsetBetween(offsets, offset + 1, offset + length))
            {
                continue; // 后缀共享或被别的名字串包住：动它会连带改坏别人
            }
            string name = assign[offset];
            if (Encoding.UTF8.GetByteCount(name) > length)
            {
                continue; // 放不下就放弃——原地改写不允许变长
            }
            byte[] raw = Encoding.UTF8.GetBytes(name);
            int fileOffset = strings.Start + offset;
            Array.Copy(raw, 0, bytes, fileOffset, raw.Length);
            Array.Clear(bytes, fileOffset + raw.Length, length - raw.Length);
            _applied[offset] = name;
        }

        Mapping = BuildMapping(types, memberLines);
        File.WriteAllBytes(outputPath, bytes);
        Verify(outputPath);

        if (mappingPath is not null)
        {
            string full = Path.GetFullPath(mappingPath);
            Directory.CreateDirectory(Path.GetDirectoryName(full)!);
            File.WriteAllText(full, Mapping, new UTF8Encoding(false));
        }
    }

    // ------------------------------------------------------------------
    // 引用统计
    // ------------------------------------------------------------------

    private void CollectType(MetadataReader md, TypeNode node,
        HashSet<int> wanted, HashSet<int> blocked, List<MemberLine> memberLines)
    {
        TypeDefinition td = md.GetTypeDefinition(node.Handle);

        if (node.Renameable)
        {
            Want(wanted, node.NameHandle);
            if (_rules.RenameNamespaces && !node.Nested)
            {
                Want(wanted, node.NamespaceHandle);
            }
        }
        else
        {
            Want(blocked, node.NameHandle);
            Want(blocked, node.NamespaceHandle);
        }

        foreach (FieldDefinitionHandle fh in td.GetFields())
        {
            FieldDefinition fd = md.GetFieldDefinition(fh);
            string name = md.GetString(fd.Name);
            bool ok = node.Renameable
                && !_rules.KeepsMember(node.FullName, name)
                && IsPlainIdentifier(name)
                && (fd.Attributes & (FieldAttributes.Literal | FieldAttributes.SpecialName
                                     | FieldAttributes.RTSpecialName)) == 0;
            if (ok)
            {
                Want(wanted, fd.Name);
                memberLines.Add(new MemberLine(fd.Name, node.FullName, name));
            }
            else
            {
                Want(blocked, fd.Name);
            }
        }

        foreach (MethodDefinitionHandle mh in td.GetMethods())
        {
            MethodDefinition mdef = md.GetMethodDefinition(mh);
            string name = md.GetString(mdef.Name);
            MethodAttributes attrs = mdef.Attributes;
            bool virtualLike = (attrs & (MethodAttributes.SpecialName | MethodAttributes.RTSpecialName
                    | MethodAttributes.Virtual | MethodAttributes.Abstract)) != 0;
            bool pinvoke = (attrs & MethodAttributes.PinvokeImpl) != 0;
            bool ok = node.Renameable
                && !_rules.KeepsMember(node.FullName, name)
                && IsPlainIdentifier(name)
                && !IsReservedMethodName(name)
                && !virtualLike
                && !pinvoke;
            if (ok)
            {
                Want(wanted, mdef.Name);
                memberLines.Add(new MemberLine(mdef.Name, node.FullName, name));
            }
            else
            {
                Want(blocked, mdef.Name);
            }

            // P/Invoke 的入口名默认就是方法名，两者共用同一个字符串偏移：方法本身不改名，
            // 但这里显式占住它，免得被别的实体「借用」同一个偏移改写掉。
            if (pinvoke)
            {
                Want(blocked, mdef.GetImport().Name);
            }
            foreach (ParameterHandle ph in mdef.GetParameters())
            {
                Want(blocked, md.GetParameter(ph).Name);
            }
            foreach (GenericParameterHandle gph in mdef.GetGenericParameters())
            {
                Want(blocked, md.GetGenericParameter(gph).Name);
            }
        }

        // 属性/事件名与泛型参数名不动：反射、数据绑定、JSON 反序列化都按名字找它们。
        foreach (PropertyDefinitionHandle ph in td.GetProperties())
        {
            Want(blocked, md.GetPropertyDefinition(ph).Name);
        }
        foreach (EventDefinitionHandle eh in td.GetEvents())
        {
            Want(blocked, md.GetEventDefinition(eh).Name);
        }
        foreach (GenericParameterHandle gph in td.GetGenericParameters())
        {
            Want(blocked, md.GetGenericParameter(gph).Name);
        }
    }

    /// <summary>程序集名、模块名、外部引用、资源名一律不改，但要占住自己的偏移，防止被改名波及。</summary>
    private static void CollectForeignNames(MetadataReader md, HashSet<string> selfKeys, HashSet<int> blocked)
    {
        Want(blocked, md.GetModuleDefinition().Name);
        if (md.IsAssembly)
        {
            Want(blocked, md.GetAssemblyDefinition().Name);
        }
        foreach (AssemblyReferenceHandle h in md.AssemblyReferences)
        {
            Want(blocked, md.GetAssemblyReference(h).Name);
        }
        // 模块引用名（P/Invoke 的 "kernel32.dll" 之类）靠名字定位本机模块，占住偏移防止被改名波及。
        for (int row = 1; row <= md.GetTableRowCount(TableIndex.ModuleRef); row++)
        {
            Want(blocked, md.GetModuleReference(MetadataTokens.ModuleReferenceHandle(row)).Name);
        }
        foreach (ManifestResourceHandle h in md.ManifestResources)
        {
            Want(blocked, md.GetManifestResource(h).Name);
        }
        foreach (ExportedTypeHandle h in md.ExportedTypes)
        {
            ExportedType et = md.GetExportedType(h);
            Want(blocked, et.Name);
            Want(blocked, et.Namespace);
        }
        foreach (TypeReferenceHandle h in md.TypeReferences)
        {
            TypeReference tr = md.GetTypeReference(h);
            string ns = md.GetString(tr.Namespace);
            string name = md.GetString(tr.Name);
            if (selfKeys.Contains(ns.Length == 0 ? name : ns + "." + name))
            {
                continue; // 指向本程序集自己的类型：偏移与 TypeDef 一致，跟着一起改才对
            }
            Want(blocked, tr.Name);
            Want(blocked, tr.Namespace);
        }
        foreach (MemberReferenceHandle h in md.MemberReferences)
        {
            Want(blocked, md.GetMemberReference(h).Name);
        }
    }

    private static void Want(HashSet<int> set, StringHandle handle)
    {
        if (handle.IsNil)
        {
            return;
        }
        int offset = MetadataTokens.GetHeapOffset(handle);
        if (offset > 0)
        {
            set.Add(offset);
        }
    }

    // ------------------------------------------------------------------
    // 类型图
    // ------------------------------------------------------------------

    private sealed class TypeNode
    {
        public TypeDefinitionHandle Handle;
        public StringHandle NameHandle;
        public StringHandle NamespaceHandle;
        public bool Nested;
        public string OuterFullName = "";
        public string FullName = "";
        public string SimpleName = "";
        public string Namespace = "";
        public bool Renameable;
    }

    private sealed record MemberLine(StringHandle Handle, string TypeFullName, string Name);

    private List<TypeNode> BuildTypes(MetadataReader md)
    {
        var list = new List<TypeNode>();
        var byHandle = new Dictionary<TypeDefinitionHandle, TypeNode>();
        var memo = new Dictionary<TypeDefinitionHandle, string>();

        foreach (TypeDefinitionHandle h in md.TypeDefinitions)
        {
            TypeDefinition td = md.GetTypeDefinition(h);
            var node = new TypeNode
            {
                Handle = h,
                NameHandle = td.Name,
                NamespaceHandle = td.Namespace,
                Nested = td.IsNested,
                SimpleName = md.GetString(td.Name),
                Namespace = md.GetString(td.Namespace),
            };
            list.Add(node);
            byHandle[h] = node;
        }

        string FullOf(TypeDefinitionHandle h)
        {
            if (memo.TryGetValue(h, out string? cached))
            {
                return cached;
            }
            TypeDefinition td = md.GetTypeDefinition(h);
            string simple = md.GetString(td.Name);
            string result;
            if (td.IsNested)
            {
                result = FullOf(td.GetDeclaringType()) + "+" + simple;
            }
            else
            {
                string ns = md.GetString(td.Namespace);
                result = ns.Length == 0 ? simple : ns + "." + simple;
            }
            memo[h] = result;
            return result;
        }

        foreach (TypeNode node in list)
        {
            node.FullName = FullOf(node.Handle);
            node.Renameable = IsPlainIdentifier(node.SimpleName)
                && !node.SimpleName.StartsWith("<", StringComparison.Ordinal)
                && !_rules.KeepsType(node.FullName);
            if (node.Nested)
            {
                node.OuterFullName = FullOf(md.GetTypeDefinition(node.Handle).GetDeclaringType());
            }
        }

        // 外层被保留时嵌套类一并保留：BAML / x:Name / 事件处理器都挂在外层类型上。
        foreach (TypeNode node in list.Where(n => n.Nested))
        {
            node.Renameable &= byHandle[md.GetTypeDefinition(node.Handle).GetDeclaringType()].Renameable;
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private string BuildMapping(List<TypeNode> types, List<MemberLine> memberLines)
    {
        var byName = types.ToDictionary(t => t.FullName, StringComparer.Ordinal);
        var cache = new Dictionary<string, string>(StringComparer.Ordinal);

        string NewFullName(TypeNode node)
        {
            if (cache.TryGetValue(node.FullName, out string? cached))
            {
                return cached;
            }
            string simple = _applied.TryGetValue(MetadataTokens.GetHeapOffset(node.NameHandle), out string? renamed)
                ? renamed
                : node.SimpleName;
            string result;
            if (node.Nested)
            {
                result = NewFullName(byName[node.OuterFullName]) + "+" + simple;
            }
            else
            {
                string ns = _rules.RenameNamespaces
                    && !node.NamespaceHandle.IsNil
                    && _applied.TryGetValue(MetadataTokens.GetHeapOffset(node.NamespaceHandle), out string? nsNew)
                        ? nsNew
                        : node.Namespace;
                result = ns.Length == 0 ? simple : ns + "." + simple;
            }
            cache[node.FullName] = result;
            return result;
        }

        var lines = new List<string>();
        foreach (TypeNode node in types)
        {
            string newFull = NewFullName(node);
            if (!string.Equals(newFull, node.FullName, StringComparison.Ordinal))
            {
                RenamedTypes++;
                lines.Add($"{node.FullName} -> {newFull}");
            }
        }

        foreach (MemberLine line in memberLines)
        {
            if (!_applied.TryGetValue(MetadataTokens.GetHeapOffset(line.Handle), out string? renamed))
            {
                continue;
            }
            RenamedMembers++;
            lines.Add($"{line.TypeFullName}::{line.Name} -> {NewFullName(byName[line.TypeFullName])}::{renamed}");
        }

        return lines.Count == 0 ? "" : string.Join(Environment.NewLine, lines) + Environment.NewLine;
    }

    private static void Verify(string path)
    {
        try
        {
            using var pe = new PEReader(File.OpenRead(path));
            MetadataReader md = pe.GetMetadataReader();
            foreach (TypeDefinitionHandle h in md.TypeDefinitions)
            {
                TypeDefinition td = md.GetTypeDefinition(h);
                _ = md.GetString(td.Name);
                _ = md.GetString(td.Namespace);
                foreach (MethodDefinitionHandle mh in td.GetMethods())
                {
                    MethodDefinition mdef = md.GetMethodDefinition(mh);
                    _ = md.GetString(mdef.Name);
                    if (mdef.RelativeVirtualAddress != 0)
                    {
                        _ = pe.GetMethodBody(mdef.RelativeVirtualAddress).GetILBytes();
                    }
                }
            }
        }
        catch (Exception e)
        {
            File.Delete(path);
            throw new InvalidDataException($"混淆产物回读失败，已删除产物：{e.Message}", e);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static bool IsPlainIdentifier(string name)
    {
        if (name.Length == 0 || char.IsDigit(name[0]))
        {
            return false;
        }
        foreach (char c in name)
        {
            if (c != '_' && !char.IsLetterOrDigit(c))
            {
                return false;
            }
        }
        return true;
    }

    private static bool IsReservedMethodName(string name) =>
        name.StartsWith("get_", StringComparison.Ordinal)
        || name.StartsWith("set_", StringComparison.Ordinal)
        || name.StartsWith("add_", StringComparison.Ordinal)
        || name.StartsWith("remove_", StringComparison.Ordinal)
        || name.StartsWith("op_", StringComparison.Ordinal);

    private static string ShortName(int index)
    {
        var sb = new StringBuilder();
        int i = index;
        do
        {
            sb.Insert(0, (char)('a' + (i % 26)));
            i = (i / 26) - 1;
        }
        while (i >= 0);
        return sb.ToString();
    }
}