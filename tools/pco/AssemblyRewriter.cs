using System.Buffers.Binary;
using System.Collections.Immutable;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace PaccManager.Pco;

/// <summary>
/// 程序集往返重写器：把源 PE 完整读一遍，再用 <see cref="MetadataBuilder"/> 原样重建，
/// 顺带支持往产物里追加新类型/字段/方法（字符串解密类、反调试类等都要靠它落进去）。
///
/// <para><b>为什么必须重建整套元数据：</b>注入新类型要给 TypeDef / MethodDef / Param / MemberRef
/// 这些表加行。行是紧密排列的，加一行就得搬动它后面所有表，本质上等价于重建，不如一次做对。</para>
///
/// <para><b>关键约定：不改动任何原有行的相对顺序。</b>原有行按原顺序重新写入，新行一律追加在末尾。
/// 这样一来原有行的行号（也就是所有元数据令牌）保持不变——签名 blob、自定义特性 blob、IL 里的
/// 类型/方法/字段令牌全都不用重映射，只有 #US 堆（字符串字面量）因为要重建、偏移会变，需要单独重映射。
/// 这是能把这件工作做对的核心简化。</para>
/// </summary>
internal sealed class AssemblyRewriter
{
    private readonly byte[] _source;
    private readonly PEReader _pe;
    private readonly MetadataReader _md;
    private readonly MetadataBuilder _builder = new();
    private readonly BlobBuilder _ilStream = new();
    private readonly BlobBuilder _mappedFieldData = new();
    private readonly BlobBuilder _managedResources = new();
    private readonly MethodBodyStreamEncoder _bodyEncoder;

    /// <summary>旧 #US 令牌 -> 新 #US 令牌。只有字面量字符串需要重映射，因为 #US 堆是重建的。</summary>
    private readonly Dictionary<int, int> _userStringMap = [];

    /// <summary>加密时自己放进去的新 #US 令牌，重映射阶段必须跳过（它们不是旧令牌）。</summary>
    private readonly HashSet<int> _freshUserStrings = [];

    // ---- 注入相关：所有新行一律追加在原有行之后，原有令牌不受影响 ----
    private readonly List<PendingType> _newTypes = [];
    private readonly List<(EntityHandle Scope, string Namespace, string Name)> _newTypeRefs = [];
    private readonly List<(EntityHandle Parent, string Name, byte[] Signature)> _newMemberRefs = [];
    private readonly List<byte[]> _newLocalSignatures = [];
    private readonly List<(string Name, Version Version, string Culture, byte[] Token, AssemblyFlags Flags)> _newAssemblyRefs = [];
    private readonly List<string> _newModuleRefs = [];
    private readonly List<(MethodDefinitionHandle Method, PendingImport Import)> _newMethodImports = [];
    private readonly Dictionary<string, EntityHandle> _typeRefMemo = [];
    private readonly List<Func<MethodDefinitionHandle, IlMethodBody, IlMethodBody?>> _transforms = [];

    private byte _stringKey;
    private int _decryptGetToken;
    private int _antiDebugStartToken;
    private int _integrityVerifyToken;
    private ProxyGenerator? _proxy;

    /// <summary>字符串加密开关。开启时会注入 __Decrypt 并重写所有可加密的 ldstr。</summary>
    public bool EncryptStrings { get; set; }

    /// <summary>完整性校验开关。开启时会在产物末尾附加 32 字节 SHA-256 尾部。</summary>
    public bool IntegrityTrailer { get; set; }

    /// <summary>被当作入口注入完整性校验调用的方法名（默认 App.OnStartup）。</summary>
    public string IntegrityHook { get; set; } = "OnStartup";

    /// <summary>是否真的注入过完整性校验调用。</summary>
    public bool IntegrityInjected { get; private set; }

    /// <summary>反调试开关。开启时注入 __AntiDebug 并在入口方法开头调用 Start()。</summary>
    public bool AntiDebugEnabled { get; private set; }

    /// <summary>被当作入口注入反调试调用的方法名（默认 App.OnStartup）。</summary>
    public string AntiDebugHook { get; set; } = "OnStartup";

    /// <summary>是否真的注入过反调试调用。</summary>
    public bool AntiDebugInjected { get; private set; }

    /// <summary>被加密的字符串字面量个数（#US 堆的 ldstr 与 const 字段的 Constant 行，统计用）。</summary>
    public int EncryptedStrings { get; private set; }

    /// <summary>被改写过的方法数（统计用）。</summary>
    public int RewrittenMethods { get; private set; }

    /// <summary>控制流平坦化开关。</summary>
    public bool ControlFlowEnabled { get; private set; }

    /// <summary>被平坦化的方法数（统计用）。</summary>
    public int FlattenedMethods { get; private set; }

    /// <summary>引用代理开关。</summary>
    public bool ProxyEnabled { get; private set; }

    /// <summary>被改写成间接调用的调用点个数（统计用）。</summary>
    public int ProxiedCalls => _proxy?.ProxiedCalls ?? 0;

    /// <summary>变换期间要读签名等元数据，供注入/平坦化模块使用。</summary>
    internal MetadataReader Metadata => _md;

    /// <summary>追加一个方法体变换。多个变换按登记顺序串联，返回 null 表示这一步不改。</summary>
    public void AddTransform(Func<MethodDefinitionHandle, IlMethodBody, IlMethodBody?> transform) =>
        _transforms.Add(transform);

    public AssemblyRewriter(byte[] source)
    {
        _source = source;
        _pe = new PEReader(ImmutableCollectionsMarshal.AsImmutableArray(source));
        _md = _pe.GetMetadataReader();
        _bodyEncoder = new MethodBodyStreamEncoder(_ilStream);
    }

    // ------------------------------------------------------------------
    // 注入接口：供 StringEncryptor / AntiDebug / Integrity 使用
    // ------------------------------------------------------------------

    public int OriginalTypeCount => _md.TypeDefinitions.Count;

    /// <summary>开启字符串加密并指定密钥。</summary>
    public void EnableStringEncryption(byte key)
    {
        _stringKey = key;
        EncryptStrings = true;
    }

    /// <summary>登记一个待注入的类型，返回它的 TypeDef 令牌。行号按登记顺序在原有类型之后顺延。</summary>
    internal int AddInjectedType(PendingType type)
    {
        _newTypes.Add(type);
        return 0x02000000 | (_md.TypeDefinitions.Count + _newTypes.Count);
    }

    /// <summary>注入类型内第 <paramref name="methodIndex"/> 个方法的 MethodDef 令牌（跨注入类型累计）。</summary>
    internal int InjectedMethodToken(int methodIndex) =>
        0x06000000 | (_md.MethodDefinitions.Count + methodIndex + 1);

    /// <summary>注入的第 <paramref name="fieldIndex"/> 个字段的 FieldDef 令牌（跨注入类型累计）。</summary>
    internal int InjectedFieldToken(int fieldIndex) =>
        0x04000000 | (_md.FieldDefinitions.Count + fieldIndex + 1);

    /// <summary>已登记的注入方法总数，供下一个注入类型算自己的方法令牌基址。</summary>
    internal int InjectedMethodCount => _newTypes.Sum(t => t.Methods.Count);

    /// <summary>已登记的注入字段总数。</summary>
    internal int InjectedFieldCount => _newTypes.Sum(t => t.Fields.Count);

    /// <summary>取（必要时新建）本机模块引用（ModuleRef），返回其句柄。P/Invoke 靠它定位模块。</summary>
    internal EntityHandle RequireModuleRef(string name)
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.ModuleRef); row++)
        {
            var h = MetadataTokens.ModuleReferenceHandle(row);
            if (_md.GetString(_md.GetModuleReference(h).Name) == name)
            {
                return h;
            }
        }
        if (!_newModuleRefs.Contains(name, StringComparer.Ordinal))
        {
            _newModuleRefs.Add(name);
        }
        return MetadataTokens.ModuleReferenceHandle(
            _md.GetTableRowCount(TableIndex.ModuleRef) + _newModuleRefs.IndexOf(name) + 1);
    }

    /// <summary>取（必要时新建）类型引用，返回其令牌。<paramref name="assembly"/> 非空时限定引用所在程序集。</summary>
    internal int RequireTypeRef(string ns, string name, string? assembly = null)
    {
        string key = assembly + "|" + ns + "|" + name;
        if (_typeRefMemo.TryGetValue(key, out EntityHandle known))
        {
            return MetadataTokens.GetToken(known);
        }
        foreach (TypeReferenceHandle th in _md.TypeReferences)
        {
            TypeReference tr = _md.GetTypeReference(th);
            if (_md.GetString(tr.Name) != name || _md.GetString(tr.Namespace) != ns)
            {
                continue;
            }
            if (assembly is not null && ScopeAssemblyName(tr.ResolutionScope) != assembly)
            {
                continue;
            }
            _typeRefMemo[key] = th;
            return MetadataTokens.GetToken(th);
        }

        var handle = MetadataTokens.TypeReferenceHandle(_md.TypeReferences.Count + _newTypeRefs.Count + 1);
        _newTypeRefs.Add((assembly is null ? CoreLibraryScope() : RequireAssemblyRef(assembly), ns, name));
        _typeRefMemo[key] = handle;
        return MetadataTokens.GetToken(handle);
    }

    /// <summary>取（必要时新建）程序集引用，返回其句柄。</summary>
    internal EntityHandle RequireAssemblyRef(string name)
    {
        foreach (AssemblyReferenceHandle h in _md.AssemblyReferences)
        {
            if (_md.GetString(_md.GetAssemblyReference(h).Name) == name)
            {
                return h;
            }
        }
        foreach ((string Name, _, _, _, _) in _newAssemblyRefs)
        {
            if (Name == name)
            {
                return MetadataTokens.AssemblyReferenceHandle(_md.AssemblyReferences.Count + IndexOfNewRef(name) + 1);
            }
        }

        // 新引用的版本/公钥标识套用已有的框架引用：同一个运行时的框架程序集共用一套标识，
        // 凭空编一个版本号会让运行期绑定失败。优先抄 System.Runtime，别把应用自身的引用标识抄过来。
        // 注意框架引用的 PublicKeyOrToken 在 .NET Core 里常常是空的，空也要照抄——照抄才是「和其余框架引用一致」。
        Version version = new(0, 0, 0, 0);
        byte[] token = [];
        foreach (AssemblyReferenceHandle h in _md.AssemblyReferences)
        {
            AssemblyReference a = _md.GetAssemblyReference(h);
            if (string.Equals(_md.GetString(a.Name), "System.Runtime", StringComparison.Ordinal))
            {
                version = a.Version;
                token = a.PublicKeyOrToken.IsNil ? [] : _md.GetBlobBytes(a.PublicKeyOrToken);
                break;
            }
        }
        if (version == new Version(0, 0, 0, 0))
        {
            foreach (AssemblyReferenceHandle h in _md.AssemblyReferences)
            {
                AssemblyReference a = _md.GetAssemblyReference(h);
                string existing = _md.GetString(a.Name);
                if (existing.StartsWith("System.", StringComparison.Ordinal) || existing is "mscorlib" or "netstandard")
                {
                    version = a.Version;
                    token = a.PublicKeyOrToken.IsNil ? [] : _md.GetBlobBytes(a.PublicKeyOrToken);
                    break;
                }
            }
        }
        _newAssemblyRefs.Add((name, version, "", token, (AssemblyFlags)0));
        return MetadataTokens.AssemblyReferenceHandle(_md.AssemblyReferences.Count + _newAssemblyRefs.Count);
    }

    private int IndexOfNewRef(string name)
    {
        for (int i = 0; i < _newAssemblyRefs.Count; i++)
        {
            if (_newAssemblyRefs[i].Name == name)
            {
                return i;
            }
        }
        return -1;
    }

    private string ScopeAssemblyName(EntityHandle scope) =>
        scope.Kind == HandleKind.AssemblyReference
            ? _md.GetString(_md.GetAssemblyReference((AssemblyReferenceHandle)scope).Name)
            : "";

    /// <summary>取（必要时新建）方法成员引用，返回 MemberRef 令牌。</summary>
    internal int RequireMethodRef(int parentTypeToken, string name, byte[] signature)
    {
        int row = _md.MemberReferences.Count + _newMemberRefs.Count + 1;
        _newMemberRefs.Add((MetadataTokens.EntityHandle(parentTypeToken), name, signature));
        return 0x0A000000 | row;
    }

    /// <summary>占用一个局部变量签名行，返回其行号（StandaloneSig 表，追加在原有之后）。</summary>
    internal int ReserveLocalSignature(byte[] blob)
    {
        _newLocalSignatures.Add(blob);
        return _md.GetTableRowCount(TableIndex.StandAloneSig) + _newLocalSignatures.Count;
    }

    /// <summary>在核心库（System.Runtime / mscorlib 等）里找一个程序集引用，作为新增类型引用的解析作用域。</summary>
    private EntityHandle CoreLibraryScope()
    {
        foreach (TypeReferenceHandle th in _md.TypeReferences)
        {
            TypeReference tr = _md.GetTypeReference(th);
            if (tr.ResolutionScope.Kind != HandleKind.AssemblyReference)
            {
                continue;
            }
            string n = _md.GetString(_md.GetAssemblyReference((AssemblyReferenceHandle)tr.ResolutionScope).Name);
            if (n is "System.Runtime" or "System.Private.CoreLib" or "mscorlib" or "netstandard")
            {
                return tr.ResolutionScope;
            }
        }
        foreach (AssemblyReferenceHandle ah in _md.AssemblyReferences)
        {
            return ah;
        }
        throw new BadImageFormatException("程序集没有任何程序集引用，无法新增类型引用");
    }

    private void PlanInjections()
    {
        if (EncryptStrings && _decryptGetToken == 0)
        {
            AddInjectedType(StringEncryptor.BuildDecryptType(this, _stringKey));
            _decryptGetToken = InjectedMethodToken(0);
        }
        if (AntiDebugEnabled && _antiDebugStartToken == 0)
        {
            int methodBase = InjectedMethodCount;
            int fieldBase = InjectedFieldCount;
            AddInjectedType(AntiDebug.BuildType(this, methodBase, fieldBase));
            _antiDebugStartToken = InjectedMethodToken(methodBase + AntiDebug.StartIndex);
        }
        if (IntegrityTrailer && _integrityVerifyToken == 0)
        {
            int methodBase = InjectedMethodCount;
            int fieldBase = InjectedFieldCount;
            AddInjectedType(IntegrityCheck.BuildType(this, methodBase, fieldBase));
            _integrityVerifyToken = InjectedMethodToken(methodBase + IntegrityCheck.VerifyIndex);
        }
        if (ProxyEnabled && _proxy is null)
        {
            // 代理类型排在所有注入类型之后，它的方法令牌基址才接得上。
            _proxy = ProxyGenerator.Create(this);
        }
    }

    /// <summary>打开反调试：注入 __AntiDebug，并在 <paramref name="hookMethod"/> 开头调用 Start()。</summary>
    public void EnableAntiDebug(string hookMethod = "OnStartup")
    {
        AntiDebugEnabled = true;
        AntiDebugHook = hookMethod;
        _transforms.Add(InjectAntiDebugCall);
    }

    /// <summary>把入口方法体前面插一条 <c>call __AntiDebug::Start()</c>，其余指令与异常边界顺移。</summary>
    private IlMethodBody? InjectAntiDebugCall(MethodDefinitionHandle handle, IlMethodBody model)
    {
        if (_md.GetString(_md.GetMethodDefinition(handle).Name) != AntiDebugHook)
        {
            return null;
        }
        AntiDebugInjected = true;

        var output = new List<IlInstruction>(model.Instructions.Count + 1)
        {
            // 插入指令不对应任何旧偏移，给个永不命中的哨兵，免得抢走标签落点。
            new() { OpCode = 0x28, Operand = _antiDebugStartToken, Offset = -1 },
        };
        output.AddRange(model.Instructions);

        (byte[] il, Dictionary<int, int> map) =
            IlAssembler.Assemble(output, IlAssembler.Boundaries(model.Exceptions));
        return model.With(il, model.MaxStack + 1, IlAssembler.Remap(model.Exceptions, map));
    }

    /// <summary>打开完整性校验：注入 __Integrity，并在 <paramref name="hookMethod"/> 开头调用 Verify()。</summary>
    public void EnableIntegrity(string hookMethod = "OnStartup")
    {
        IntegrityTrailer = true;
        IntegrityHook = hookMethod;
        _transforms.Add(InjectIntegrityCall);
    }

    /// <summary>把入口方法体前面插一条 <c>call __Integrity::Verify()</c>，其余指令与异常边界顺移。</summary>
    private IlMethodBody? InjectIntegrityCall(MethodDefinitionHandle handle, IlMethodBody model)
    {
        if (_md.GetString(_md.GetMethodDefinition(handle).Name) != IntegrityHook)
        {
            return null;
        }
        IntegrityInjected = true;

        var output = new List<IlInstruction>(model.Instructions.Count + 1)
        {
            new() { OpCode = 0x28, Operand = _integrityVerifyToken, Offset = -1 },
        };
        output.AddRange(model.Instructions);

        (byte[] il, Dictionary<int, int> map) =
            IlAssembler.Assemble(output, IlAssembler.Boundaries(model.Exceptions));
        return model.With(il, model.MaxStack + 1, IlAssembler.Remap(model.Exceptions, map));
    }

    /// <summary>打开控制流平坦化：把满足条件的方法体摊成 switch 状态机。</summary>
    public void EnableControlFlow()
    {
        ControlFlowEnabled = true;
        _transforms.Add((_, model) =>
        {
            IlMethodBody? flattened = ControlFlowFlattener.Flatten(this, model);
            if (flattened is not null)
            {
                FlattenedMethods++;
            }
            return flattened;
        });
    }

    /// <summary>打开引用代理：把直接调用换成语义等价的 __Proxy 间接调用。</summary>
    public void EnableProxy()
    {
        ProxyEnabled = true;
        _transforms.Add((handle, model) => _proxy?.Rewrite(handle, model));
    }

    public byte[] Run()
    {
        PEHeaders headers = _pe.PEHeaders;
        PEHeader pe = headers.PEHeader ?? throw new BadImageFormatException("缺少 PE 可选头");
        CorHeader cor = headers.CorHeader ?? throw new BadImageFormatException("不是托管程序集");

        // 注入计划要先定下来：注入方法的 IL 需要新的类型引用/成员引用/局部签名，
        // 这些行的令牌在写元数据之前就得确定，注入的调用点才能把令牌填进去。
        PlanInjections();

        // 方法体变换要排在所有 Add* 表写入之前：变换过程可能登记新的类型引用、成员引用、
        // 局部签名（平坦化要追加 state 局部），这些行必须赶在对应表写完之前定下来。
        TableCounts counts = TableCounts.Of(_md);
        List<MethodBodyPlan> bodies = EncodeMethodBodies(counts);

        AddModule();
        AddAssembly();
        AddTypeReferences();
        AddAssemblyReferences();
        AddAssemblyFiles();
        AddModuleReferences();
        AddTypeSpecifications();
        AddStandaloneSignatures();

        AddTypeDefinitionsAndMembers(counts, bodies);
        AddInterfaceImplementations();
        AddMemberReferences();
        AddConstants();
        AddCustomAttributes();
        AddDeclarativeSecurity();
        AddClassAndFieldLayout();
        AddMethodSemantics();
        AddMethodImplementations();
        AddMethodImports();
        AddFieldRvas();
        AddExportedTypes();
        AddManifestResources();
        AddNestedTypes();
        AddGenericParameters();
        AddGenericParameterConstraints();
        AddMethodSpecifications();

        var rootBuilder = new MetadataRootBuilder(_builder, _md.MetadataVersion);
        CopyManagedResources(cor);

        CorFlags flags = cor.Flags;
        if ((flags & CorFlags.StrongNameSigned) != 0)
        {
            // 重建会作废强名称签名。产物是随包发布的 app-local 程序集，去掉签名比留一个失效签名安全。
            flags &= ~CorFlags.StrongNameSigned;
        }

        var peHeader = new PEHeaderBuilder(
            machine: headers.CoffHeader.Machine,
            sectionAlignment: pe.SectionAlignment,
            fileAlignment: pe.FileAlignment,
            imageBase: pe.ImageBase,
            majorLinkerVersion: 0,
            minorLinkerVersion: 0,
            majorOperatingSystemVersion: 0,
            minorOperatingSystemVersion: 0,
            majorImageVersion: 0,
            minorImageVersion: 0,
            majorSubsystemVersion: pe.MajorSubsystemVersion,
            minorSubsystemVersion: pe.MinorSubsystemVersion,
            subsystem: pe.Subsystem,
            dllCharacteristics: pe.DllCharacteristics,
            imageCharacteristics: headers.CoffHeader.Characteristics,
            sizeOfStackReserve: pe.SizeOfStackReserve,
            sizeOfStackCommit: pe.SizeOfStackCommit,
            sizeOfHeapReserve: pe.SizeOfHeapReserve,
            sizeOfHeapCommit: pe.SizeOfHeapCommit);

        var peBuilder = new ManagedPEBuilder(
            peHeader,
            rootBuilder,
            _ilStream,
            mappedFieldData: _mappedFieldData,
            managedResources: _managedResources,
            nativeResources: null,
            debugDirectoryBuilder: null,
            strongNameSignatureSize: 0,
            entryPoint: EntryPointOf(cor),
            flags: flags);

        var image = new BlobBuilder();
        peBuilder.Serialize(image);
        byte[] bytes = image.ToArray();

        if (IntegrityTrailer)
        {
            // 尾部 32 字节放「PE 镜像部分的 SHA-256」。哈希只盖镜像本身，不含尾部，
            // 否则自指算不出来（见 IntegrityCheck 的说明）。
            byte[] withTrailer = new byte[bytes.Length + IntegrityCheck.TrailerSize];
            bytes.CopyTo(withTrailer, 0);
            SHA256.HashData(bytes).CopyTo(withTrailer, bytes.Length);
            return withTrailer;
        }
        return bytes;
    }

    /// <summary>原始各表行数，注入成员要靠它预先算出追加行的行号。</summary>
    private readonly record struct TableCounts(int Types, int Fields, int Methods, int Params, int Properties, int Events)
    {
        public static TableCounts Of(MetadataReader md) => new(
            md.TypeDefinitions.Count,
            md.FieldDefinitions.Count,
            md.MethodDefinitions.Count,
            md.GetTableRowCount(TableIndex.Param),
            md.PropertyDefinitions.Count,
            md.EventDefinitions.Count);
    }

    private sealed record MethodBodyPlan(int BodyOffset, ParameterHandle ParameterList);

    // ------------------------------------------------------------------
    // 顶层表
    // ------------------------------------------------------------------

    private void AddModule()
    {
        ModuleDefinition module = _md.GetModuleDefinition();
        _builder.AddModule(0, Str(module.Name), _builder.GetOrAddGuid(_md.GetGuid(module.Mvid)), default, default);
    }

    private void AddAssembly()
    {
        if (!_md.IsAssembly)
        {
            return;
        }
        AssemblyDefinition a = _md.GetAssemblyDefinition();
        _builder.AddAssembly(Str(a.Name), a.Version, Str(a.Culture), Blob(a.PublicKey), a.Flags, a.HashAlgorithm);
    }

    private void AddTypeReferences()
    {
        foreach (TypeReferenceHandle h in _md.TypeReferences)
        {
            TypeReference tr = _md.GetTypeReference(h);
            // 行号保持不变，ResolutionScope 里的编码令牌可以直接沿用。
            _builder.AddTypeReference(tr.ResolutionScope, Str(tr.Namespace), Str(tr.Name));
        }
        // 注入代码用到的新类型引用追加在末尾，行号 = 原有行数 + 登记序。
        foreach ((EntityHandle scope, string ns, string name) in _newTypeRefs)
        {
            _builder.AddTypeReference(scope, _builder.GetOrAddString(ns), _builder.GetOrAddString(name));
        }
    }

    private void AddAssemblyReferences()
    {
        foreach (AssemblyReferenceHandle h in _md.AssemblyReferences)
        {
            AssemblyReference a = _md.GetAssemblyReference(h);
            _builder.AddAssemblyReference(Str(a.Name), a.Version, Str(a.Culture), Blob(a.PublicKeyOrToken), a.Flags, Blob(a.HashValue));
        }
        // 注入代码用到的新程序集引用追加在末尾，行号 = 原有行数 + 登记序。
        foreach ((string name, Version version, string culture, byte[] token, AssemblyFlags flags) in _newAssemblyRefs)
        {
            _builder.AddAssemblyReference(
                _builder.GetOrAddString(name),
                version,
                culture.Length == 0 ? default : _builder.GetOrAddString(culture),
                token.Length == 0 ? default : _builder.GetOrAddBlob(token),
                flags,
                default);
        }
    }

    private void AddAssemblyFiles()
    {
        foreach (AssemblyFileHandle h in _md.AssemblyFiles)
        {
            AssemblyFile f = _md.GetAssemblyFile(h);
            _builder.AddAssemblyFile(Str(f.Name), Blob(f.HashValue), f.ContainsMetadata);
        }
    }

    private void AddModuleReferences()
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.ModuleRef); row++)
        {
            _builder.AddModuleReference(Str(_md.GetModuleReference(MetadataTokens.ModuleReferenceHandle(row)).Name));
        }
        foreach (string name in _newModuleRefs)
        {
            _builder.AddModuleReference(_builder.GetOrAddString(name));
        }
    }

    private void AddTypeSpecifications()
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.TypeSpec); row++)
        {
            _builder.AddTypeSpecification(Blob(_md.GetTypeSpecification(MetadataTokens.TypeSpecificationHandle(row)).Signature));
        }
    }

    private void AddStandaloneSignatures()
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.StandAloneSig); row++)
        {
            var h = MetadataTokens.StandaloneSignatureHandle(row);
            _builder.AddStandaloneSignature(Blob(_md.GetStandaloneSignature(h).Signature));
        }
        foreach (byte[] blob in _newLocalSignatures)
        {
            _builder.AddStandaloneSignature(_builder.GetOrAddBlob(blob));
        }
    }

    // ------------------------------------------------------------------
    // 方法体
    // ------------------------------------------------------------------

    private List<MethodBodyPlan> EncodeMethodBodies(TableCounts counts)
    {
        var plans = new List<MethodBodyPlan>(counts.Methods);
        int paramRow = 1;
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            MethodDefinition mdef = _md.GetMethodDefinition(h);
            int paramCount = mdef.GetParameters().Count;
            var firstParam = MetadataTokens.ParameterHandle(paramRow);
            paramRow += paramCount;

            int bodyOffset = mdef.RelativeVirtualAddress != 0 ? EncodeOneBody(h, mdef) : -1;
            plans.Add(new MethodBodyPlan(bodyOffset, firstParam));
        }
        return plans;
    }

    private int EncodeOneBody(MethodDefinitionHandle handle, MethodDefinition mdef)
    {
        MethodBodyBlock block = _pe.GetMethodBody(mdef.RelativeVirtualAddress);
        IReadOnlyList<IlException> exceptions = block.ExceptionRegions.Select(IlException.From).ToArray();
        bool initLocals = block.LocalVariablesInitialized;
        IlMethodBody model = IlReader.Read(
            block.GetILBytes() ?? [],
            block.MaxStack,
            initLocals,
            block.LocalSignature,
            block.ExceptionRegions);

        // 多个变换按登记顺序串联：每步基于上一步的结果继续改，返回 null 表示这一步不动它。
        IlMethodBody current = model;
        bool rewritten = false;
        int stackNeed = block.MaxStack;
        foreach (Func<MethodDefinitionHandle, IlMethodBody, IlMethodBody?> transform in _transforms)
        {
            IlMethodBody? next = transform(handle, current);
            if (next is not null)
            {
                current = next;
                rewritten = true;
                stackNeed = Math.Max(stackNeed, next.MaxStack);
            }
        }

        byte[] il;
        int maxStack;
        Dictionary<int, int>? offsetMap = null;
        StandaloneSignatureHandle localSignature = block.LocalSignature;
        if (!rewritten)
        {
            il = model.Il;
            maxStack = block.MaxStack;
        }
        else
        {
            RewrittenMethods++;
            il = current.Il;
            // 变换会往栈上多放值（状态变量），预留一点余量，宁大勿小。
            maxStack = Math.Max(stackNeed, block.MaxStack + 8);
            initLocals = current.InitLocals;
            exceptions = current.Exceptions;
            localSignature = current.LocalSignature;
        }

        if (EncryptStrings && _decryptGetToken != 0)
        {
            il = EncryptLdstr(il, exceptions, out offsetMap);
        }
        il = RemapUserStrings(il);

        MethodBodyAttributes attrs = initLocals ? MethodBodyAttributes.InitLocals : MethodBodyAttributes.None;
        MethodBodyStreamEncoder.MethodBody body = _bodyEncoder.AddMethodBody(
            il.Length,
            maxStack,
            exceptions.Count,
            hasSmallExceptionRegions: false,
            localSignature,
            attrs);
        new BlobWriter(body.Instructions).WriteBytes(il, 0, il.Length);
        IReadOnlyList<IlException> written = offsetMap is null
            ? exceptions
            : IlAssembler.Remap(exceptions, offsetMap);
        foreach (IlException r in written)
        {
            body.ExceptionRegions.Add(r.Kind, r.TryOffset, r.TryLength, r.HandlerOffset, r.HandlerLength, r.CatchType);
        }
        return body.Offset;
    }

    /// <summary>
    /// 把方法体里可加密的 <c>ldstr</c> 换成「密文字面量 + call __Decrypt::Get」。
    /// 只有长度变化的分支/异常边界需要修正；密文自身作为新 #US 令牌登记，重映射阶段会跳过。
    /// </summary>
    private byte[] EncryptLdstr(byte[] il, IReadOnlyList<IlException> regions,
        out Dictionary<int, int>? offsetMap)
    {
        IlMethodBody model = IlReader.Read(il, 0, false, default, []);
        List<IlInstruction>? output = null;

        for (int i = 0; i < model.Instructions.Count; i++)
        {
            IlInstruction ins = model.Instructions[i];
            string? plain = ins.OpCode == 0x72 ? LdstrValue((int)ins.Operand!) : null;
            if (plain is null || plain.Length < 2)
            {
                output?.Add(ins);
                continue;
            }

            output ??= [.. model.Instructions.Take(i)];
            int cipherToken = MetadataTokens.GetToken(
                _builder.GetOrAddUserString(StringEncryptor.Encrypt(plain, _stringKey)));
            _freshUserStrings.Add(cipherToken);
            output.Add(new IlInstruction { OpCode = 0x72, Operand = cipherToken, Offset = ins.Offset });
            // 插入的 call 不对应任何旧偏移，给一个永不命中的哨兵值，免得抢走标签落点。
            output.Add(new IlInstruction { OpCode = 0x28, Operand = _decryptGetToken, Offset = -1 });
            EncryptedStrings++;
        }

        if (output is null)
        {
            offsetMap = null;
            return il;
        }

        (byte[] assembled, Dictionary<int, int> map) = IlAssembler.Assemble(output, IlAssembler.Boundaries(regions));
        offsetMap = map;
        return assembled;
    }

    private string? LdstrValue(int token)
    {
        int offset = token & 0x00FFFFFF;
        return offset == 0 ? null : _md.GetUserString(MetadataTokens.UserStringHandle(offset));
    }

    private void AddTypeDefinitionsAndMembers(TableCounts counts, List<MethodBodyPlan> bodies)
    {
        int fieldRow = 1;
        int methodRow = 1;
        int propertyRow = 1;
        int eventRow = 1;
        int bodyIndex = 0;

        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            TypeDefinition td = _md.GetTypeDefinition(h);
            int fieldStart = fieldRow;
            int methodStart = methodRow;
            fieldRow += td.GetFields().Count;
            methodRow += td.GetMethods().Count;

            // 字段/方法/参数/属性/事件必须按「类型顺序 + 类型内顺序」写入，
            // 各表的 List 列（fieldList/methodList/paramList/propertyList/eventList）才不会错位。
            _builder.AddTypeDefinition(
                td.Attributes,
                Str(td.Namespace),
                Str(td.Name),
                td.BaseType,
                MetadataTokens.FieldDefinitionHandle(fieldStart),
                MetadataTokens.MethodDefinitionHandle(methodStart));

            foreach (FieldDefinitionHandle fh in td.GetFields())
            {
                FieldDefinition fd = _md.GetFieldDefinition(fh);
                _builder.AddFieldDefinition(fd.Attributes, Str(fd.Name), Blob(fd.Signature));
            }

            foreach (MethodDefinitionHandle mh in td.GetMethods())
            {
                MethodDefinition mdef = _md.GetMethodDefinition(mh);
                MethodBodyPlan plan = bodies[bodyIndex++];
                _builder.AddMethodDefinition(
                    mdef.Attributes, mdef.ImplAttributes, Str(mdef.Name), Blob(mdef.Signature),
                    plan.BodyOffset, plan.ParameterList);
                foreach (ParameterHandle ph in mdef.GetParameters())
                {
                    Parameter p = _md.GetParameter(ph);
                    _builder.AddParameter(p.Attributes, Str(p.Name), p.SequenceNumber);
                }
            }

            if (td.GetProperties().Count > 0)
            {
                _builder.AddPropertyMap(h, MetadataTokens.PropertyDefinitionHandle(propertyRow));
                foreach (PropertyDefinitionHandle ph in td.GetProperties())
                {
                    PropertyDefinition p = _md.GetPropertyDefinition(ph);
                    _builder.AddProperty(p.Attributes, Str(p.Name), Blob(p.Signature));
                    propertyRow++;
                }
            }
            if (td.GetEvents().Count > 0)
            {
                _builder.AddEventMap(h, MetadataTokens.EventDefinitionHandle(eventRow));
                foreach (EventDefinitionHandle eh in td.GetEvents())
                {
                    EventDefinition e = _md.GetEventDefinition(eh);
                    _builder.AddEvent(e.Attributes, Str(e.Name), e.Type);
                    eventRow++;
                }
            }
        }

        AddInjectedTypesAndMembers();
    }

    /// <summary>把注入类型追加到 TypeDef/Field/Method/Param 表末尾，List 列游标接着原有行继续。</summary>
    private void AddInjectedTypesAndMembers()
    {
        int fieldRow = _md.FieldDefinitions.Count + 1;
        int methodRow = _md.MethodDefinitions.Count + 1;
        int paramRow = _md.GetTableRowCount(TableIndex.Param) + 1;

        foreach (PendingType type in _newTypes)
        {
            int firstField = fieldRow;
            int firstMethod = methodRow;

            // 字段表必须整体排在方法表之前，所以先落字段再落方法。
            foreach (PendingField f in type.Fields)
            {
                _builder.AddFieldDefinition(
                    f.Attributes, _builder.GetOrAddString(f.Name), _builder.GetOrAddBlob(f.Signature));
                fieldRow++;
            }

            foreach (PendingMethod m in type.Methods)
            {
                int bodyOffset = m.Il.Length == 0 ? -1 : EncodeInjectedBody(m);
                var methodHandle = MetadataTokens.MethodDefinitionHandle(methodRow);
                _builder.AddMethodDefinition(
                    m.Attributes,
                    m.ImplAttributes,
                    _builder.GetOrAddString(m.Name),
                    _builder.GetOrAddBlob(m.Signature),
                    bodyOffset,
                    MetadataTokens.ParameterHandle(paramRow));
                if (m.Import is not null)
                {
                    _newMethodImports.Add((methodHandle, m.Import));
                }

                for (int i = 0; i < m.Parameters.Count; i++)
                {
                    _builder.AddParameter(ParameterAttributes.None,
                        _builder.GetOrAddString(m.Parameters[i]), i + 1);
                }
                paramRow += m.Parameters.Count;
                methodRow++;
            }

            _builder.AddTypeDefinition(
                type.Attributes,
                _builder.GetOrAddString(type.Namespace),
                _builder.GetOrAddString(type.Name),
                MetadataTokens.EntityHandle(type.BaseTypeToken),
                MetadataTokens.FieldDefinitionHandle(firstField),
                MetadataTokens.MethodDefinitionHandle(firstMethod));
        }
    }

    private int EncodeInjectedBody(PendingMethod m)
    {
        StandaloneSignatureHandle localSig = m.LocalSignatureRow == 0
            ? default
            : MetadataTokens.StandaloneSignatureHandle(m.LocalSignatureRow);
        MethodBodyStreamEncoder.MethodBody body = _bodyEncoder.AddMethodBody(
            m.Il.Length,
            m.MaxStack,
            0,
            hasSmallExceptionRegions: false,
            localSig,
            m.InitLocals ? MethodBodyAttributes.InitLocals : MethodBodyAttributes.None);
        new BlobWriter(body.Instructions).WriteBytes(m.Il, 0, m.Il.Length);
        return body.Offset;
    }

    // ------------------------------------------------------------------
    // 其余表
    // ------------------------------------------------------------------

    private void AddInterfaceImplementations()
    {
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            foreach (InterfaceImplementationHandle ih in _md.GetTypeDefinition(h).GetInterfaceImplementations())
            {
                _builder.AddInterfaceImplementation(h, _md.GetInterfaceImplementation(ih).Interface);
            }
        }
    }

    private void AddMemberReferences()
    {
        foreach (MemberReferenceHandle h in _md.MemberReferences)
        {
            MemberReference mr = _md.GetMemberReference(h);
            _builder.AddMemberReference(mr.Parent, Str(mr.Name), Blob(mr.Signature));
        }
        foreach ((EntityHandle parent, string name, byte[] signature) in _newMemberRefs)
        {
            _builder.AddMemberReference(parent, _builder.GetOrAddString(name), _builder.GetOrAddBlob(signature));
        }
    }

    private void AddConstants()
    {
        // HasConstant 编码：Field(0) -> Param(1) -> Property(2)，按这个顺序写入行号才对齐。
        foreach (FieldDefinitionHandle h in _md.FieldDefinitions)
        {
            FieldDefinition fd = _md.GetFieldDefinition(h);
            ConstantHandle c = fd.GetDefaultValue();
            if (!c.IsNil)
            {
                _builder.AddConstant(h, FieldConstantValue(fd, _md.GetConstant(c)));
            }
        }
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            foreach (ParameterHandle ph in _md.GetMethodDefinition(h).GetParameters())
            {
                ConstantHandle c = _md.GetParameter(ph).GetDefaultValue();
                if (!c.IsNil)
                {
                    _builder.AddConstant(ph, ReadConstantValue(_md.GetConstant(c)));
                }
            }
        }
        foreach (PropertyDefinitionHandle h in _md.PropertyDefinitions)
        {
            ConstantHandle c = _md.GetPropertyDefinition(h).GetDefaultValue();
            if (!c.IsNil)
            {
                _builder.AddConstant(h, ReadConstantValue(_md.GetConstant(c)));
            }
        }
    }

    private object? ReadConstantValue(Constant c)
    {
        BlobReader r = _md.GetBlobReader(c.Value);
        return c.TypeCode switch
        {
            ConstantTypeCode.Boolean => r.ReadBoolean(),
            ConstantTypeCode.Char => r.ReadChar(),
            ConstantTypeCode.SByte => r.ReadSByte(),
            ConstantTypeCode.Byte => r.ReadByte(),
            ConstantTypeCode.Int16 => r.ReadInt16(),
            ConstantTypeCode.UInt16 => r.ReadUInt16(),
            ConstantTypeCode.Int32 => r.ReadInt32(),
            ConstantTypeCode.UInt32 => r.ReadUInt32(),
            ConstantTypeCode.Int64 => r.ReadInt64(),
            ConstantTypeCode.UInt64 => r.ReadUInt64(),
            ConstantTypeCode.Single => r.ReadSingle(),
            ConstantTypeCode.Double => r.ReadDouble(),
            ConstantTypeCode.String => r.ReadUTF16(r.Length),
            ConstantTypeCode.NullReference => null,
            _ => throw new BadImageFormatException($"未知常量类型 {c.TypeCode}"),
        };
    }

    /// <summary>
    /// const 字段的字面量存在 Constant 表里，不在 #US 堆，<see cref="EncryptLdstr"/> 扫不到它。
    /// 用到 const 的地方编译期都已内联成 ldstr（已被加密），运行期不会再去读这个值，
    /// 所以这里把常量值本身也换成密文，反编译才看不到明文。<b>只动字段</b>：
    /// 参数默认值是在调用方省略实参时由运行时读取的，改成密文就真的改变行为了。
    /// </summary>
    private object? FieldConstantValue(FieldDefinition field, Constant constant)
    {
        object? value = ReadConstantValue(constant);
        if (EncryptStrings
            && value is string text
            && text.Length >= 2
            && (field.Attributes & FieldAttributes.Literal) != 0)
        {
            EncryptedStrings++;
            return StringEncryptor.Encrypt(text, _stringKey);
        }
        return value;
    }

    private void AddCustomAttributes()
    {
        foreach (CustomAttributeHandle h in _md.CustomAttributes)
        {
            CustomAttribute ca = _md.GetCustomAttribute(h);
            _builder.AddCustomAttribute(ca.Parent, ca.Constructor, Blob(ca.Value));
        }
    }

    private void AddDeclarativeSecurity()
    {
        // HasDeclSecurity 编码：TypeDef(0) -> MethodDef(1) -> Assembly(2)。
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            foreach (DeclarativeSecurityAttributeHandle sh in _md.GetTypeDefinition(h).GetDeclarativeSecurityAttributes())
            {
                AddSecurity(h, sh);
            }
        }
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            foreach (DeclarativeSecurityAttributeHandle sh in _md.GetMethodDefinition(h).GetDeclarativeSecurityAttributes())
            {
                AddSecurity(h, sh);
            }
        }
        if (_md.IsAssembly)
        {
            foreach (DeclarativeSecurityAttributeHandle sh in _md.GetAssemblyDefinition().GetDeclarativeSecurityAttributes())
            {
                AddSecurity(MetadataTokens.EntityHandle(TableIndex.Assembly, 1), sh);
            }
        }
    }

    private void AddSecurity(EntityHandle parent, DeclarativeSecurityAttributeHandle h)
    {
        DeclarativeSecurityAttribute d = _md.GetDeclarativeSecurityAttribute(h);
        _builder.AddDeclarativeSecurityAttribute(parent, d.Action, Blob(d.PermissionSet));
    }

    private void AddClassAndFieldLayout()
    {
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            TypeLayout layout = _md.GetTypeDefinition(h).GetLayout();
            if (!layout.IsDefault)
            {
                _builder.AddTypeLayout(h, (ushort)layout.PackingSize, (uint)layout.Size);
            }
        }
        foreach (FieldDefinitionHandle h in _md.FieldDefinitions)
        {
            int offset = _md.GetFieldDefinition(h).GetOffset();
            if (offset != -1)
            {
                _builder.AddFieldLayout(h, offset);
            }
        }
    }

    private void AddMethodSemantics()
    {
        // HasSemantics 编码：Event(0) 在前、Property(1) 在后。MethodSemantics 行不被任何令牌引用，
        // 组内顺序无所谓，但行数与分组要跟原来一致。
        foreach (EventDefinitionHandle h in _md.EventDefinitions)
        {
            EventAccessors a = _md.GetEventDefinition(h).GetAccessors();
            AddSemantics(h, a.Adder, MethodSemanticsAttributes.Adder);
            AddSemantics(h, a.Remover, MethodSemanticsAttributes.Remover);
            AddSemantics(h, a.Raiser, MethodSemanticsAttributes.Raiser);
            foreach (MethodDefinitionHandle m in a.Others)
            {
                _builder.AddMethodSemantics(h, MethodSemanticsAttributes.Other, m);
            }
        }
        foreach (PropertyDefinitionHandle h in _md.PropertyDefinitions)
        {
            PropertyAccessors a = _md.GetPropertyDefinition(h).GetAccessors();
            AddSemantics(h, a.Getter, MethodSemanticsAttributes.Getter);
            AddSemantics(h, a.Setter, MethodSemanticsAttributes.Setter);
            foreach (MethodDefinitionHandle m in a.Others)
            {
                _builder.AddMethodSemantics(h, MethodSemanticsAttributes.Other, m);
            }
        }
    }

    private void AddSemantics(EntityHandle association, MethodDefinitionHandle method, MethodSemanticsAttributes semantics)
    {
        if (!method.IsNil)
        {
            _builder.AddMethodSemantics(association, semantics, method);
        }
    }

    private void AddMethodImplementations()
    {
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            foreach (MethodImplementationHandle mh in _md.GetTypeDefinition(h).GetMethodImplementations())
            {
                MethodImplementation mi = _md.GetMethodImplementation(mh);
                _builder.AddMethodImplementation(h, mi.MethodBody, mi.MethodDeclaration);
            }
        }
    }

    private void AddMethodImports()
    {
        // ImplMap 按 MemberForwarded（MethodDef 行号）排序，按方法行序写入即可。
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            MethodDefinition mdef = _md.GetMethodDefinition(h);
            if ((mdef.Attributes & System.Reflection.MethodAttributes.PinvokeImpl) == 0)
            {
                continue;
            }
            MethodImport mi = mdef.GetImport();
            _builder.AddMethodImport(h, mi.Attributes, Str(mi.Name), mi.Module);
        }
        // 注入的 P/Invoke 方法行号最大，追加在末尾仍满足 ImplMap 按 MemberForwarded 的排序要求。
        foreach ((MethodDefinitionHandle method, PendingImport import) in _newMethodImports)
        {
            _builder.AddMethodImport(
                method,
                import.Attributes,
                _builder.GetOrAddString(import.Name),
                (ModuleReferenceHandle)RequireModuleRef(import.Module));
        }
    }

    private void AddFieldRvas()
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.FieldRva); row++)
        {
            var h = MetadataTokens.FieldDefinitionHandle(row);
            int rva = _md.GetFieldDefinition(h).GetRelativeVirtualAddress();
            if (rva == 0)
            {
                continue;
            }
            byte[] data = ReadFieldData(_md.GetFieldDefinition(h), rva);
            int offset = _mappedFieldData.Count;
            _mappedFieldData.WriteBytes(data, 0, data.Length);
            _builder.AddFieldRelativeVirtualAddress(h, offset);
        }
    }

    private byte[] ReadFieldData(FieldDefinition fd, int rva)
    {
        int size = FieldDataSize(fd);
        if (size < 0)
        {
            throw new BadImageFormatException("无法确定静态字段初始数据长度");
        }
        if (size == 0)
        {
            return [];
        }
        var data = new byte[size];
        _pe.GetSectionData(rva).GetContent(0, size).CopyTo(data);
        return data;
    }

    /// <summary>静态字段初始数据长度：值类型的显式布局大小，或基础类型宽度。</summary>
    private int FieldDataSize(FieldDefinition fd)
    {
        BlobReader r = _md.GetBlobReader(fd.Signature);
        r.ReadByte(); // FIELD 调用约定
        SignatureTypeCode code = r.ReadSignatureTypeCode();
        if (code == SignatureTypeCode.TypeHandle)
        {
            EntityHandle type = r.ReadTypeHandle();
            return type.Kind == HandleKind.TypeDefinition
                ? (int)_md.GetTypeDefinition((TypeDefinitionHandle)type).GetLayout().Size
                : -1;
        }
        return code switch
        {
            SignatureTypeCode.Boolean or SignatureTypeCode.SByte or SignatureTypeCode.Byte => 1,
            SignatureTypeCode.Char or SignatureTypeCode.Int16 or SignatureTypeCode.UInt16 => 2,
            SignatureTypeCode.Int32 or SignatureTypeCode.UInt32 or SignatureTypeCode.Single => 4,
            SignatureTypeCode.Int64 or SignatureTypeCode.UInt64 or SignatureTypeCode.Double => 8,
            _ => -1,
        };
    }

    private void AddExportedTypes()
    {
        foreach (ExportedTypeHandle h in _md.ExportedTypes)
        {
            ExportedType e = _md.GetExportedType(h);
            _builder.AddExportedType(e.Attributes, Str(e.Namespace), Str(e.Name), e.Implementation, e.GetTypeDefinitionId());
        }
    }

    private void AddManifestResources()
    {
        foreach (ManifestResourceHandle h in _md.ManifestResources)
        {
            ManifestResource r = _md.GetManifestResource(h);
            _builder.AddManifestResource(r.Attributes, Str(r.Name), r.Implementation, (uint)r.Offset);
        }
    }

    private void AddNestedTypes()
    {
        // NestedClass 按 NestedClass 列（TypeDef 行号）排序；按类型行序写入即可。
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            TypeDefinition td = _md.GetTypeDefinition(h);
            if (td.IsNested)
            {
                _builder.AddNestedType(h, td.GetDeclaringType());
            }
        }
    }

    private void AddGenericParameters()
    {
        // GenericParam 按 Owner（TypeOrMethodDef：TypeDef=0 在前、MethodDef=1 在后）排序。
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            foreach (GenericParameterHandle gh in _md.GetTypeDefinition(h).GetGenericParameters())
            {
                GenericParameter g = _md.GetGenericParameter(gh);
                _builder.AddGenericParameter(h, g.Attributes, Str(g.Name), g.Index);
            }
        }
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            foreach (GenericParameterHandle gh in _md.GetMethodDefinition(h).GetGenericParameters())
            {
                GenericParameter g = _md.GetGenericParameter(gh);
                _builder.AddGenericParameter(h, g.Attributes, Str(g.Name), g.Index);
            }
        }
    }

    private void AddGenericParameterConstraints()
    {
        foreach (TypeDefinitionHandle h in _md.TypeDefinitions)
        {
            foreach (GenericParameterHandle gh in _md.GetTypeDefinition(h).GetGenericParameters())
            {
                AddConstraintsOf(gh);
            }
        }
        foreach (MethodDefinitionHandle h in _md.MethodDefinitions)
        {
            foreach (GenericParameterHandle gh in _md.GetMethodDefinition(h).GetGenericParameters())
            {
                AddConstraintsOf(gh);
            }
        }
    }

    private void AddConstraintsOf(GenericParameterHandle gh)
    {
        foreach (GenericParameterConstraintHandle ch in _md.GetGenericParameter(gh).GetConstraints())
        {
            _builder.AddGenericParameterConstraint(gh, _md.GetGenericParameterConstraint(ch).Type);
        }
    }

    private void AddMethodSpecifications()
    {
        for (int row = 1; row <= _md.GetTableRowCount(TableIndex.MethodSpec); row++)
        {
            MethodSpecification ms = _md.GetMethodSpecification(MetadataTokens.MethodSpecificationHandle(row));
            _builder.AddMethodSpecification(ms.Method, Blob(ms.Signature));
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private StringHandle Str(StringHandle h) => h.IsNil ? default : _builder.GetOrAddString(_md.GetString(h));

    private BlobHandle Blob(BlobHandle h) => h.IsNil ? default : _builder.GetOrAddBlob(_md.GetBlobBytes(h));

    /// <summary>把方法体里 ldstr 的 #US 令牌换成重建后的新令牌（#US 堆偏移会变，其余令牌不变）。</summary>
    private byte[] RemapUserStrings(byte[] il)
    {
        byte[]? copy = null;
        int p = 0;
        while (p < il.Length)
        {
            int at = p;
            int b = il[p++];
            ushort code;
            if (b == 0xFE)
            {
                if (p >= il.Length)
                {
                    break;
                }
                code = (ushort)(0xFE00 | il[p++]);
            }
            else
            {
                code = (ushort)b;
            }

            if (code == 0x72)
            {
                int oldToken = BinaryPrimitives.ReadInt32LittleEndian(il.AsSpan(p, 4));
                if (_freshUserStrings.Contains(oldToken))
                {
                    p += 4;
                    continue;
                }
                int newToken = MapUserString(oldToken);
                if (newToken != oldToken)
                {
                    copy ??= (byte[])il.Clone();
                    BinaryPrimitives.WriteInt32LittleEndian(copy.AsSpan(p, 4), newToken);
                }
                p += 4;
                continue;
            }
            p = SkipOperand(il, p, code, at);
        }
        return copy ?? il;
    }

    private int MapUserString(int oldToken)
    {
        if (_userStringMap.TryGetValue(oldToken, out int mapped))
        {
            return mapped;
        }
        int offset = oldToken & 0x00FFFFFF;
        string value = offset == 0 ? "" : _md.GetUserString(MetadataTokens.UserStringHandle(offset));
        UserStringHandle handle = _builder.GetOrAddUserString(value);
        int newToken = MetadataTokens.GetToken(handle);
        _userStringMap[oldToken] = newToken;
        return newToken;
    }

    /// <summary>按操作码跳过操作数；不认识的指令直接失败，避免解析错位。</summary>
    private static int SkipOperand(byte[] il, int p, ushort code, int at)
    {
        if (!IlOpcodes.TryRead(code, out OperandType kind))
        {
            throw new BadImageFormatException($"偏移 {at}：未知 IL 操作码 0x{code:X4}");
        }
        return kind switch
        {
            OperandType.InlineNone => p,
            OperandType.ShortInlineI or OperandType.ShortInlineVar or OperandType.ShortInlineBrTarget => p + 1,
            OperandType.InlineVar => p + 2,
            OperandType.InlineSwitch => p + 4 + 4 * BinaryPrimitives.ReadInt32LittleEndian(il.AsSpan(p, 4)),
            OperandType.InlineI8 or OperandType.InlineR => p + 8,
            _ => p + 4,
        };
    }

    private void CopyManagedResources(CorHeader cor)
    {
        if (cor.ResourcesDirectory.Size <= 0)
        {
            return;
        }
        int start = RvaToOffset(cor.ResourcesDirectory.RelativeVirtualAddress);
        _managedResources.WriteBytes(_source, start, cor.ResourcesDirectory.Size);
    }

    private MethodDefinitionHandle EntryPointOf(CorHeader cor)
    {
        int token = cor.EntryPointTokenOrRelativeVirtualAddress;
        if (token == 0 || (cor.Flags & CorFlags.ILOnly) == 0)
        {
            return default;
        }
        return MetadataTokens.MethodDefinitionHandle(token & 0x00FFFFFF);
    }

    private int RvaToOffset(int rva)
    {
        foreach (SectionHeader section in _pe.PEHeaders.SectionHeaders)
        {
            if (rva >= section.VirtualAddress && rva < section.VirtualAddress + section.SizeOfRawData)
            {
                return section.PointerToRawData + (rva - section.VirtualAddress);
            }
        }
        throw new BadImageFormatException($"RVA 0x{rva:X8} 不落在任何节内");
    }
}