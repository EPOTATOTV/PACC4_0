# PRL API 参考

这份文档按包列出 `com.potatotv.prl` 的公开 API：编译、执行、规则引擎、宿主与沙箱、类型与静态检查、调试器、性能分析、版本发布、字节码。签名以源码为准，异常与边界也一并写明。

看语言层面的东西去 [语言规范](language-spec.md)，看宿主怎么接去 [宿主接入指南](host-api-guide.md)，标准库函数表在 [标准库参考](stdlib-reference.md)。

标注约定：

- `record` 是 Java record，访问器名与分量同名。
- 「抛」一栏只写引擎自己抛出的异常；参数为 `null` 时各处的兜底行为单独注明。
- 版本号基准是 `Prl.VERSION`，与 `pom.xml` 的 `<version>` 对齐。

运行时值的 Java 表示（各 API 之间的数据都用这些类型）：`int`→`Long`、`float`→`Double`、`bool`→`Boolean`、`string`→`String`、`list[T]`→`List`、`set[T]`→`Set`、`map[K,V]`→`Map`、`tuple[...]`→不可变 `List`。

## 1. 入口门面 `Prl`

`com.potatotv.prl.Prl` 是 final 类，没有状态，不能实例化。每个方法现建一个 VM 或编译器。

| 成员 | 说明 |
|---|---|
| `static final String VERSION` | 引擎版本，`"1.0.0"`。只是给人看的字符串，兼容性判据是 `PrlBytecode.FORMAT_VERSION` |
| `static PrlBytecode compile(String source)` | 编译，失败抛 `PrlException` |
| `static CompileResult compileChecked(String source)` | 编译，错误以诊断返回，不抛 |
| `static Object run(String source, Map<String,Object> input)` | 编译并执行，宿主用 `PrlHostContext.EMPTY` |
| `static Object run(String source, PrlHostContext host, Map<String,Object> input)` | 同上，可带宿主；`host` 为 `null` 时按 `EMPTY` 处理 |
| `static RuleManager engine()` | 新建规则管理器（空宿主） |
| `static RuleManager engine(PrlHostContext host)` | 同上，带宿主 |
| `static PrlAnalyzer analyzer()` | 新建静态分析器（空宿主） |
| `static PrlAnalyzer analyzer(PrlHostContext host)` | 同上，带宿主 |
| `static List<String> stdlibFunctions()` | 标准库函数名，字典序 |

`run` 返回 `emit_alert` 的产物（`DetectionResult`），没有告警时返回 `null`。要复用宿主或观察者，直接构造 `RuleManager` / `PrlVm`，别走这个门面。

## 2. 编译 `compiler`

### `PrlCompiler`

源码到字节码的门面，final 类。流水线是 `Parser → TypeChecker → IrGenerator → BytecodeCompiler`。

| 成员 | 说明 |
|---|---|
| `static final String SYNTAX_CODE` | 词法/语法诊断码，`"PRL-P"`（类型错误的码在 `TypeChecker`，是 `PRL-T`） |
| `PrlCompiler()` | 空宿主 |
| `PrlCompiler(PrlHostContext host)` | `host` 为 `null` 时按 `EMPTY` 处理 |
| `PrlBytecode compile(String source)` | 有任何错误就抛 `PrlException`。热加载与规则装载走这条 |
| `CompileResult compileChecked(String source)` | 错误以诊断返回。管理端编辑器实时检查走这条 |
| `CompileResult compileChecked(RuleFile file)` | 从已解析的 AST 编译 |

带宿主构造时取两样东西：宿主的上下文类型表与函数白名单。白名单为空（或 `null`）表示「宿主没接管任何函数」，此时不限制可调用的函数，全部交给标准库与运行期兜底。

### `CompileResult`

```java
public record CompileResult(RuleFile file, PrlBytecode bytecode,
                            TypeCheckResult types, List<Diagnostic> diagnostics)
```

- `file`：解析出的 AST，语法阶段就失败时为 `null`。
- `bytecode`：编译产物，失败时为 `null`。
- `types`：类型检查的完整结果（每个表达式的推断类型），语法阶段失败时为 `null`。
- `diagnostics`：全部诊断（含警告），已不可变。

方法：`boolean ok()`（等价于 `bytecode != null`）、`List<Diagnostic> errors()`、`List<Diagnostic> warnings()`、`String describe()`（全部诊断拼成一段文本，无诊断时返回 `"编译通过"`）。

## 3. 执行 `vm.PrlVm`

寄存器式解释器，final 类。VM 实例自身无可变状态，每次 `execute*` 新建一份执行状态，所以同一份 `PrlBytecode` 可以多线程并发执行。嵌套调用（宿主回调 lambda、lambda 套 lambda）共用同一份执行状态，指令数与超时按「一次规则执行」累计。

限额常量（§2.11.1 L2）：

| 常量 | 值 | 含义 |
|---|---|---|
| `MAX_INSTRUCTIONS` | `100000` | 单条规则指令数 |
| `TIMEOUT_NANOS` | `100000000`（100ms） | 单条规则执行时间 |
| `MAX_CALL_DEPTH` | `64` | 调用栈深度 |
| `MAX_COLLECTION_SIZE` | `10000` | 集合元素数上限（VM 侧目前用于 `a..b` 范围构造） |

构造与方法：

| 成员 | 说明 |
|---|---|
| `PrlVm()` | 空宿主 |
| `PrlVm(PrlHostContext host)` | 无观察者 |
| `PrlVm(PrlHostContext host, PrlExecutionObserver observer)` | `host`/`observer` 为 `null` 时分别按 `EMPTY` / `NONE` 处理 |
| `Object execute(PrlBytecode program, Map<String,Object> input)` | 执行入口函数。`input` 按函数形参名取值 |
| `Object executeRule(PrlBytecode program, String ruleName, Map<String,Object> input)` | 按规则名执行，字节码里没有该规则时抛 `PrlExecutionException` |
| `Object executeRule(PrlBytecode program, RuleEntry rule, Map<String,Object> input)` | 直接按 `RuleEntry` 执行 |

函数名解析顺序（§2.11.1 L3）：本程序函数表 → 宿主白名单 → 标准库；三条都不通抛 `PrlSecurityException`。

异常：超指令数抛 `PrlExecutionException`，超时抛 `PrlTimeoutException`，越界/类型错误等抛 `PrlExecutionException`；规则内抛出的其他 `RuntimeException` 会被包成 `PrlExecutionException`，不会把宿主进程带崩。

## 4. 规则引擎与热加载 `engine`

### `RuleManager`

final 类。持有「规则名 → 运行时状态」表，装载用原子替换：编译成功才 `put`，编译失败直接抛，旧规则保留。

| 成员 | 说明 |
|---|---|
| `static final int MAX_FAILURES` | `10`。累计失败超过它就自动禁用该规则 |
| `RuleManager()` | 空宿主 |
| `RuleManager(PrlHostContext host)` | 无观察者 |
| `RuleManager(PrlHostContext host, PrlExecutionObserver observer)` | 观察者挂给内部 `PrlVm`；传 `NONE` 时执行路径无额外开销 |
| `PrlHostContext host()` | 本管理器用的宿主；热加载器要用同一份来编译 |
| `RuleInstance loadRule(String name, String source)` | 编译并装载，编译失败抛 `PrlException` |
| `RuleInstance loadBytecode(String name, PrlBytecode bytecode)` | 直接装载产物（回滚、灰度用） |
| `boolean unloadRule(String name)` | 卸载，返回是否真的卸掉了 |
| `void clear()` | 清空全部规则 |
| `List<DetectionResult> executeAll(Map<String,Object> input)` | 执行全部已启用规则，按规则名字典序 |
| `Optional<DetectionResult> executeRule(String name, Map<String,Object> input)` | 执行单条规则 |
| `Optional<RuleInstance> rule(String name)` | 取运行时状态 |
| `List<String> ruleNames()` | 已装载规则名，字典序 |
| `int size()` | 规则条数 |

`executeAll` / `executeRule` 单条规则失败不打断整轮：失败计数累加，超过 `MAX_FAILURES` 自动禁用，其余继续跑。告警的 `ruleName` 由引擎在收集时补上（`emit_alert` 不知道自己在哪条规则里）。

### `RuleInstance`

final 类，一条已装载规则的运行时状态。`bytecode()`、`name()`、`loadedAtMs()`、`cooldownMillis()` 不可变；`enabled`、`lastError` 等可变状态用 volatile / 原子类，执行线程与热加载线程共享。

构造：`RuleInstance(String name, PrlBytecode bytecode, long loadedAtMs)` 与 `RuleInstance(String name, PrlBytecode bytecode, long loadedAtMs, long cooldownMillis)`。三参构造的冷却时间取字节码里第一条规则的 `cooldown`，取不到为 0。

方法：`name()`、`bytecode()`、`loadedAtMs()`、`cooldownMillis()`、`isEnabled()`、`enable()`（重新启用并清零失败计数）、`disable()`、`failureCount()`、`executionCount()`、`lastError()`、`int recordFailure(Throwable)`（返回累计失败次数）、`boolean cooldownOk(Map<String,Object>)`、`boolean cooldownOk(long nowMs)`、`void markExecuted(long nowMs)`。

冷却按「规则」而不是「玩家」计。`cooldownOk(Map)` 保留入参只为对齐设计文档签名，当前实现不按玩家分桶。

### `DetectionResult`

```java
public record DetectionResult(String ruleName, String type, double confidence,
                              Map<Object,Object> evidence, long timestampMs)
```

- `ruleName`：由 `RuleManager` 收集时补；脱离宿主单独执行时可能是空串。
- `type`：告警类型，规则自己命名，如 `"KILL_AURA"`。
- `confidence`：0~1。
- `evidence`：证据键值对，送入时做了一份不可变拷贝；允许 `null` 键/值。
- `timestampMs`：产生时间（毫秒）。

方法：`withRuleName(String name)`、`Map<String,Object> toMap()`（键为 `rule_name`/`type`/`confidence`/`evidence`/`timestamp_ms`）。

### `RuleHotLoader`

final 类，implements `AutoCloseable`。看守一个目录，文件改了自动重编。

| 成员 | 说明 |
|---|---|
| `RuleHotLoader(Path directory, RuleManager manager)` | 构造时可能抛 `IOException` |
| `void start() throws IOException` | 开始轮询 |
| `void close()` | 停止并收尾 |
| `Map<String,String> errors()` | 规则名 → 最近一次编译错误 |
| `List<String> loadedRules()` | 已加载规则名 |
| `Path directory()` | 监听的目录 |
| `void reloadAll()` | 全量重扫 |

规则名取文件名（去掉 `.prl`）。内容指纹用 SHA-256 去重，轮询间隔 200ms，编译失败保留旧规则并把错误记进 `errors()`。热加载器必须用管理器的宿主编译，否则白名单与运行期不一致。

## 5. 宿主与沙箱 `sandbox` / `runtime`

### `PrlHostContext`

接口。两个抽象方法，其余都有默认实现。

| 成员 | 说明 |
|---|---|
| `PrlHostContext EMPTY` | 空宿主：所有 `callFunction` 抛 `PrlSecurityException`，标准库照常工作，不接收副作用 |
| `Object callFunction(String name, Object[] args)` | 调用宿主接管的函数；不在白名单时抛 `PrlSecurityException` |
| `Set<String> getAvailableFunctions()` | 宿主函数白名单，同时是编译期白名单来源 |
| `Object getMember(Object target, String member)` | 默认：`PrlHostObject` 委托过来，另外放行 `Map`；读不到抛 `PrlSecurityException` |
| `Object callMethod(Object target, String method, Object[] args)` | 默认：委托 `PrlHostObject`；不支持时抛 `PrlSecurityException` |
| `void acceptAlert(DetectionResult alert)` | 默认什么都不做 |
| `void recordEvidence(String key, Object value)` | 默认什么都不做 |
| `void triggerRedScreen(String reason)` | 默认什么都不做 |
| `void log(String level, String message)` | 默认打到标准输出 |
| `void disableFeature(String feature, long durationMillis)` | 默认什么都不做（禁用客户端功能开关，不是封禁账号） |
| `HostTypeRegistry getTypes()` | 默认返回 `HostTypeRegistry.standard()` |

`getAvailableFunctions()` 返回的是**允许被调用的函数全集**，不是「宿主额外提供的那些」。集合非空时，规则里出现的每个函数名（标准库函数也算）都必须在这个集合里，否则编译期报「宿主未提供函数」。集合为空或 `null` 才算「宿主没接管任何函数」，此时不设限。

运行期解析顺序是「本程序函数表 → 宿主白名单 → 标准库」。只要函数名进了白名单，调用就先落到 `callFunction`，标准库不会被执行。宿主声明了自己的函数后，必须在 `getAvailableFunctions()` 里带上 `PrlStdlib.functionNames()`，并在 `callFunction` 里把标准库名字转交 `PrlStdlib`。

### `PrlHostObject`

接口，宿主对象可选的成员访问协议（沙箱禁反射，成员读取得由对象自己交出入口）。

- `Object getMember(String member)`：读字段，没有就抛 `PrlSecurityException`。
- `default Object callMethod(String method, Object[] args)`：默认抛 `PrlSecurityException`（即「没有方法」）。
- `default String describableMembers()`：可用于错误信息的成员清单，默认空串。

宿主想自己控管成员访问，也可以不实现它，直接覆写 `PrlHostContext.getMember` / `callMethod`。

### 沙箱边界

宿主接入时不用自己写限制，VM 会拦（值见 `PrlVm` 常量）。静态分析器另有一份固定名单，写这些名字报安全审计（`PRL-S`）：

- 动态执行与进程：`loadstring`、`require`、`eval`、`exec`、`system`、`shell`、`popen`
- 文件：`file_read`、`file_write`、`file_delete`、`open_file`
- 网络：`http_get`、`http_post`、`socket`、`connect`、`dns_query`
- 环境与反射：`getenv`、`setenv`、`class_for_name`、`new_instance`、`os_clock`、`os_time`

成员访问禁止名单：`getclass`、`class`、`classloader`、`forname`、`newinstance`、`runtime`、`exec`、`exit`。

### 运行时异常

都在 `runtime` / `debugger` 包，除 `PrlException` 外都是 `RuntimeException` 子类。

| 异常 | 场合 |
|---|---|
| `PrlException` | 编译、装载、参数校验等通用错误；有 `int line()`、`int col()`，消息格式 `[line:col] message` |
| `PrlExecutionException` | 指令数超限、越界、除零、调用目标不是函数值等 |
| `PrlTimeoutException` | 单条规则执行超过 100ms |
| `PrlSecurityException` | 函数不在白名单与标准库、成员访问被拒 |
| `PrlDebugAbortedException` | 调试会话被中止 |

### `PrlExecutionObserver` / `PrlFrameView`

观察者接口，给调试器与 Profiler 用。常量 `PrlExecutionObserver.NONE` 表示不观察（热路径上只多一次引用比较）。

回调（都是 default，可只实现关心的几个）：`onInstruction(PrlFrameView frame)`、`onRuleStart(String ruleName)`、`onRuleEnd(String ruleName, long elapsedNanos)`、`onEnterFunction(String functionName)`、`onExitFunction(String functionName, long elapsedNanos)`。

`PrlFrameView` 是当前帧的只读视图：`String functionName()`、`int line()`、`int depth()`、`Map<String,Object> variables()`（拷贝）、`List<String> callStack()`。

### `PrlStdlib`

标准库实现，`stdlib` 包。整层无状态（唯一例外是 `random` 的实例级 `Random`）。

| 成员 | 说明 |
|---|---|
| `PrlStdlib()` / `PrlStdlib(PrlHostContext)` | 种子为 0 |
| `PrlStdlib(PrlHostContext host, long seed)` | 指定随机种子 |
| `PrlStdlib withSeed(long seed)` | 复制实例、只换种子（宿主不变） |
| `static boolean supports(String name)` | 该函数名是否由标准库兜底 |
| `static List<String> functionNames()` | 标准库函数名，字典序 |
| `Object call(String name, Object[] args)` | 分发表；名字不在标准库时抛 `PrlSecurityException` |

`random` 必须确定性可复现（§2.4.5），VM 每条规则新建一个实例、用规则名派生种子。`to_float` / `to_int` / `to_string` 只登记在签名表里供类型检查用，标准库没有实现，宿主不接管就运行期抛 `PrlSecurityException`。

## 6. 类型与静态检查 `types` / `check` / `analysis`

### `HostTypeRegistry`

final 类。把源码里的类型引用（`TypeRef`）解析成 `PrlType`，并提供成员访问所需的字段/方法表。

| 成员 | 说明 |
|---|---|
| `static HostTypeRegistry standard()` | 内置默认类型表（见下） |
| `static HostTypeRegistry empty()` | 空表，宿主完全接管类型时用 |
| `HostTypeRegistry register(HostType type)` | 注册，返回自身可链式 |
| `HostType get(String name)` / `boolean contains(String name)` | 查类型 |
| `Set<String> names()` | 已注册类型名 |
| `PrlType resolve(TypeRef ref)` | 解析类型引用；未注册或泛型参数个数不对抛 `TypeException` |
| `PrlType resolve(String name, List<PrlType> args, int line, int col)` | 同上，按名字与已解析参数 |
| `Map<String,HostType> asMap()` | 全部类型的拷贝 |

`resolve` 内置基础与集合类型：`int`/`float`/`bool`/`string`/`list[...]`/`set[...]`/`map[..., ...]`/`tuple[...]`。

`standard()` 注册的类型：

| 类型 | 字段 | 方法 |
|---|---|---|
| `PlayerContext` | `id`、`name`、`platform`（string），`reputation`（int） | `is_trusted()` |
| `AttackEvent` | `target_changed`、`is_critical`（bool），`damage`、`reach`（float），`target_id`（string），`time_ms`（int） | |
| `MoveEvent` | `speed`、`direction`（float），`duration_ms`（int） | `is_airborne()`、`is_grounded()`、`is_horizontal()` |
| `MiningEvent` | `exposed`（bool），`path_straightness`、`distance_to_vein`（float），`duration_ms`（int） | `is_ore()` |
| `ClickEvent` | `interval_ms`、`position_x`、`position_y`、`position_z`（float），`time_ms`（int），`button`（string） | |
| `MouseMoveEvent` | `delta_yaw`、`delta_pitch`、`precision`（float），`time_ms`（int） | `is_horizontal()` |
| `ClickPattern` | `cps`、`interval_stddev`（float） | `is_human_like()`、`to_json()` |
| `Stats` | `mean`、`stddev`、`median`（float），`count`（int） | |
| `SystemState` | `tps`、`memory_used_mb`（float），`tick`、`online_players`（int） | `network_stable()` |
| `DetectionResult` | `type`、`rule_name`（string），`confidence`（float），`evidence`（map），`timestamp_ms`（int） | |

零参宿主方法允许省略括号：`player.is_trusted` 与 `player.is_trusted()` 等价。宿主对象在规则里是只读的，没有注册写入口。

### `HostType` / `HostFunction`

`HostType` 是 final 类，用 `HostType.builder(String name)` 构建：

- `Builder field(String name, PrlType type)`
- `Builder method(String name, PrlType returnType)`（注册零参方法）
- `Builder method(HostFunction function)`
- `HostType build()`

访问器：`name()`、`PrlType field(String)`、`boolean hasField(String)`、`HostFunction method(String)`、`boolean hasMethod(String)`、`Set<String> fieldNames()`、`Set<String> methodNames()`、`PrlType asType()`（即 `PrlType.host(name)`）。

```java
public record HostFunction(String name, List<Param> params,
                           PrlType returnType, String description) {
    public record Param(String name, PrlType type) { }
}
```

静态工厂：`HostFunction.param(String, PrlType)`、`HostFunction.of(String name, PrlType returnType, String description, Param... params)`。方法：`int arity()`、`PrlType paramType(int index)`、`int indexOfParam(String name)`（不存在返回 -1）、`String signature()`、`String signatureWithNames()`。`params` 为 `null` 时按空表处理。

### `TypeCheckResult` / `Diagnostic`

`TypeCheckResult`（final 类）保存类型检查的全部结果：

- `Map<Expression,PrlType> types()`：按 AST 节点身份索引的表达式类型表（`IdentityHashMap`）。
- `List<Diagnostic> diagnostics()`：全部诊断。
- `Optional<PrlType> typeOf(Expression)`。
- `int[] argumentOrder(Expression.CallExpr)`：具名实参的形参落位；位置实参调用返回 `null`。
- `List<Diagnostic> errors()`、`List<Diagnostic> warnings()`、`boolean ok()`（无 ERROR 级诊断才算通过）。

```java
public record Diagnostic(Level level, String code, String message, int line, int col) {
    public enum Level { ERROR, WARNING }
}
```

静态工厂 `Diagnostic.error(...)` / `Diagnostic.warning(...)`，方法 `boolean isError()`。诊断码前缀：`PRL-P` 语法、`PRL-T` 类型、`PRL-N` 空指针、`PRL-L` 无限循环、`PRL-S` 安全、`PRL-C` 冲突。

### `PrlAnalyzer`

final 类，走一遍 AST 产出四类结论。类型错误与未使用变量不重算，直接合并类型检查器的诊断。

| 常量 | 值 |
|---|---|
| `NULL_RISK_CODE` | `"PRL-N"` |
| `INFINITE_LOOP_CODE` | `"PRL-L"` |
| `SECURITY_CODE` | `"PRL-S"` |
| `CONFLICT_CODE` | `"PRL-C"` |

构造：`PrlAnalyzer()`（空宿主）、`PrlAnalyzer(PrlHostContext host)`。方法：`AnalysisResult analyze(String source)`（编译一次再分析，编译失败也照常分析）、`AnalysisResult analyze(CompileResult compile)`。静态方法：`List<RuleConflict> detectConflicts(RuleFile file)`、`Map<String,List<RuleConflict>> indexConflicts(List<RuleConflict> conflicts)`（按规则名索引）。

判据都刻意「宁可漏报不误报」：无限循环只认 `while true:`（或恒真字面量）且循环体无 `return`；空指针只认映射表下标取值与 `first`/`last`，被 `??` 兜住的不算；规则冲突只认 `when` 条件规范化后完全相同、告警类型不同。

### `AnalysisResult`

```java
public record AnalysisResult(CompileResult compile, List<Diagnostic> diagnostics,
                             List<RuleMetrics> metrics, List<RuleConflict> conflicts)
```

方法：`boolean ok()`（无错误且无冲突）、`List<Diagnostic> errors()`、`List<Diagnostic> warnings()`、`Optional<RuleMetrics> metrics(String ruleName)`、`String report()`。

### `RuleMetrics` / `RuleConflict`

```java
public record RuleMetrics(String ruleName, int cyclomaticComplexity, int maxNestingDepth,
                          int estimatedInstructions, long estimatedNanos,
                          long estimatedHeapBytes, int complexityScore)
```

常量 `HIGH_COMPLEXITY`（`40`），方法 `boolean complex()`（`complexityScore >= HIGH_COMPLEXITY`）。耗时按每条指令 10ns 估算，内存按槽位/元素/帧的固定系数估算，都是静态近似。

```java
public record RuleConflict(String ruleA, String ruleB, String condition,
                           String reason, String suggestion)
```

冲突不走 `Diagnostic`：冲突判据分不出「两个结论互相矛盾」和「两个都合法的类型名」，只报建议不报错。它出现在 `AnalysisResult.report()` 的冲突行里。

### AST `ast`

规则文件的 AST 在 `ast` 包，主要类型：`RuleFile`、`Rule`、`RuleInput`、`LetDecl`、`TypeRef`，语句 `Statement`（含 `IfStmt`、`ForStmt`、`WhileStmt`、`ReturnStmt`、`AssignStmt`、`ExprStmt`），表达式 `Expression`（含字面量、`BinaryExpr`、`UnaryExpr`、`CallExpr`、`MethodCall`、`MemberAccess`、`IndexAccess`、`LambdaExpr`、`ListLiteral`、`SetLiteral`、`MapLiteral`、`TupleLiteral`、`RangeExpr`、`PipeExpr`、`IfExpr`、`InterpolatedString`）。`Expression` 是 sealed 接口，`Analyzer` 的规范化打印对每个分支穷举。

## 7. 调试器 `debugger`

### `PrlDebugger`

final 类，implements `PrlExecutionObserver`。被调试的规则在 `start` 起的后台线程里跑，命中暂停条件时该线程阻塞在命令队列上；控制线程通过 `awaitPause` 取现场、用 `resume`/单步放行。断点粒度是**源码行**，不是字节码指令。

常量 `MAX_LOG_RECORDS`（`1000`）。`enum StepMode { CONTINUE, STEP_INTO, STEP_OVER, STEP_OUT }`。

构造：`PrlDebugger()`、`PrlDebugger(PrlHostContext host)`。

断点管理：

| 方法 | 说明 |
|---|---|
| `void setBreakpoint(int line)` | 无条件断点 |
| `void setBreakpoint(int line, Predicate<PrlFrameView> condition)` | 条件断点；`condition` 为 `null` 时退化为无条件 |
| `void addLogpoint(int line)` | 日志点：不中断，只记录 |
| `void addLogpoint(int line, Consumer<LogRecord> sink)` | 日志点并把记录同时交给 `sink` |
| `boolean removeBreakpoint(int line)` | 去掉该行的断点或日志点 |
| `void clearBreakpoints()` | 清空 |
| `List<Breakpoint> breakpoints()` | 全部断点与日志点，按行号升序 |
| `List<LogRecord> logRecords()` | 已记录日志，最多 `MAX_LOG_RECORDS` 条 |
| `void clearLogRecords()` | 清空日志 |

行号必须从 1 开始，否则抛 `PrlException`。

会话：

| 方法 | 说明 |
|---|---|
| `void start(PrlBytecode program, String ruleName, Map<String,Object> input)` | 立即返回；`ruleName` 为 `null` 或字节码里没这条规则时执行入口函数 |
| `void start(PrlBytecode program, Map<String,Object> input)` | 执行入口函数 |
| `void start(RuleInstance rule, Map<String,Object> input)` | 按规则实例 |
| `Optional<DebugState> awaitPause(long timeout, TimeUnit unit)` | 等下一次暂停；结束/中止/超时返回空，用 `isRunning()` 区分「没停」和「跑完」 |
| `Object awaitFinish(long timeout, TimeUnit unit)` | 等执行结束并返回规则返回值；超时或停在断点上抛 `PrlException` |
| `void resume()` / `stepInto()` / `stepOver()` / `stepOut()` | 放行与单步；规则没停在断点上时抛 `PrlException` |
| `void abort()` | 中止执行 |
| `void close()` | 关会话（还在跑就先中止再等收尾） |
| `boolean isRunning()` / `boolean isPaused()` | 状态 |
| `Optional<DebugState> state()` | 最近一次暂停的现场 |

同一条规则不能被两个 `start` 同时调试，重复调用抛 `PrlException`。条件断点的条件是 `Predicate<PrlFrameView>`，不是 PRL 源码字符串——要编译一段 PRL 表达式得有 `input` 里每个字段的宿主类型，那份类型信息只有管理端编辑器手上有。

### 调试数据

```java
public record DebugState(String functionName, int line, int depth,
                         Map<String,Object> variables, List<String> callStack)
```

静态 `DebugState.snapshot(PrlFrameView frame)`。

```java
public record Breakpoint(int line, boolean logpoint, boolean conditional)
public record LogRecord(int line, String functionName, Map<String,Object> variables)
```

## 8. 性能分析 `profiler`

### `PrlProfiler`

final 类，implements `PrlExecutionObserver`。采集三样东西：每次规则的执行时间、每个函数的**自身**耗时（不含子调用）、内存峰值（由调用方喂进来）。分位数用对数刻度直方图（512 个桶、每倍频程 8 个桶），内存是常数，P95/P99 相对误差不超过 12.5%，可复现。函数耗时按线程归属（`ThreadLocal`），多线程各自记账。

| 方法 | 说明 |
|---|---|
| `void register(String ruleName, String version)` | 登记规则，无内存基线 |
| `void register(String ruleName, String version, long estimatedHeapBytes)` | 登记规则，内存峰值基线取 `RuleMetrics#estimatedHeapBytes` |
| `void markMemory(String ruleName, long bytes)` | 喂一次实测内存占用，取历史最大值 |
| `void reset()` | 清空全部采集 |
| `List<String> rules()` | 已采集规则名，字典序 |
| `long executions(String ruleName)` | 某规则执行次数；没登记过返回 0 |
| `ProfilerReport report(String ruleName)` | 单条报告；没登记过返回全零报告 |
| `List<ProfilerReport> reports()` | 全部规则各一份 |

只有处在 `onRuleStart` / `onRuleEnd` 之间时函数耗时才归到规则名下；直接调 `PrlVm.execute` 而中间没有规则上下文时不记账。

### `ProfilerReport` / `FunctionHotspot`

```java
public record ProfilerReport(String ruleName, String version, long executions,
                             long averageNanos, long p95Nanos, long p99Nanos, long maxNanos,
                             long memoryPeakBytes, List<FunctionHotspot> hotspots,
                             List<String> suggestions)
```

方法：`String text()`（可读报告）、静态 `String formatBytes(long)`。分位数取命中桶的上界，报出的数字只偏保守。

```java
public record FunctionHotspot(String name, long calls, long selfNanos, double share)
```

`share` 是 0~1 的占比，分母是全部函数自身耗时之和。

## 9. 版本发布 `engine`

### `RuleReleaseManager`

final 类。把「编写 → 静态分析 → draft → 灰度 → 审批 → active → 回滚」这条链落成方法。构造 `RuleReleaseManager(RuleVersionStore store, RuleManager manager)`。发布成 `ACTIVE` 的瞬间会把规则原子装进 `RuleManager`，所以「发布」与「生效」是同一个动作。

| 方法 | 说明 |
|---|---|
| `AnalysisResult analyze(String source)` | 静态分析，不落库。编辑器保存前先调 |
| `RuleVersion submitDraft(String ruleName, String version, String source, String author)` | 提交为 `DRAFT`；有错误、规则名对不上、同版本号内容不同时抛 `PrlException` |
| `RuleVersion startCanary(String ruleName, String version)` | `DRAFT → TESTING`；状态不对抛 `PrlException` |
| `RuleVersion approve(String ruleName, String version, String approver)` | 审批发布为 `ACTIVE`，旧的 active 变 `DEPRECATED` 并回填成回滚目标；`approver` 为空抛 `PrlException` |
| `RuleVersion rollback(String ruleName)` | 一键回滚到 `rollbackTo`，回滚掉的版本标 `DISABLED`；无生效版本/无回滚目标/目标已禁用时抛 `PrlException` |
| `CanaryComparison compare(RuleVersion baseline, RuleVersion candidate, Map<String,Object> input)` | 同一份输入跑基线版与候选版，给出一致性 |
| `List<RuleVersion> versions(String ruleName)` | 版本历史 |
| `Optional<RuleVersion> active(String ruleName)` | 当前生效版本 |

`submitDraft` 先静态分析，有**错误**就不入库；冲突不拦（冲突检测分不出「矛盾」和「都合法」，拿它拦发布会在管理端造成死锁），冲突连同报告一起返回由人判断。`rollback` 只走 `rollbackTo` 指到的那一个版本，不做「随便挑个旧版本」。

### `CanaryComparison`

```java
public record CanaryComparison(DetectionResult baseline, DetectionResult candidate)
```

方法：`boolean agrees()`（`type` 相同且 `confidence` 之差小于 `1e-9`）、`double confidenceDelta()`（候选减基线，任一为空时 0）、`String report()`。

### 版本库

`RuleVersionStore` 接口：`void save(RuleVersion)`、`Optional<RuleVersion> find(String ruleName, String version)`、`Optional<RuleVersion> active(String ruleName)`、`List<RuleVersion> history(String ruleName)`、`List<RuleVersion> all()`。

`InMemoryRuleVersionStore`（final 类）是内存版，给单测与本地开发用，不做持久化，重启即丢。管控后端应自己实现 `RuleVersionStore` 落到数据库表上。

`RuleVersion`（final 类，因 `bytecode` 是 `byte[]` 而非 record）：构造 `RuleVersion(String ruleName, String version, String source, byte[] bytecode, String author, RuleStatus status, long createdAtMs)`，以及带 checksum / approvedBy / rollbackTo 的完整构造。访问器 `ruleName()`、`version()`、`source()`、`bytecode()`（返回副本）、`checksum()`（源码 SHA-256）、`author()`、`status()`、`createdAtMs()`、`approvedBy()`、`rollbackTo()`；方法 `withStatus(RuleStatus, String approvedBy, String rollbackTo)`。

```java
public enum RuleStatus {
    DRAFT, TESTING, ACTIVE, DEPRECATED, DISABLED;
}
```

方法 `boolean isLive()`、静态 `RuleStatus fromKeyword(String)`。

## 10. 字节码 `bytecode`

### `PrlBytecode`

final 类。常量 `FORMAT_VERSION`（`1`），这是跨版本兼容的判据。构造 `PrlBytecode(List<BytecodeFunction> functions, List<RuleEntry> rules, List<String> strings)`。

方法：`functions()`、`rules()`、`strings()`、`String string(int index)`、`int indexOf(String text)`、`BytecodeFunction function(int index)`、`BytecodeFunction function(String name)`、`RuleEntry rule(String name)`、`boolean isEmpty()`。

### `PrlcFormat`

注：这个类在 `com.potatotv.prl.bytecode` 包（不是 `compiler`）。

- `static byte[] write(PrlBytecode bytecode)`：序列化为 `.prlc` 字节。
- `static PrlBytecode read(byte[] file)`：反序列化；文件头不是 `PRLC`、版本不支持、各段越界时抛 `PrlException`。

文件头布局：Magic(`PRLC`) + Version(u16) + Flags(u8) + Rule Count(u16) + String Table / Code Section / Metadata 三段的 offset+size 共六个数（各 u32）。Code Section 里带函数表与规则表，Metadata 是 JSON。

## 11. 管理端编辑器 `prl-editor`（TypeScript）

`prl-editor` 是本模块里的独立 npm 包（`@potatotv/prl-editor`），给管控后端的管理界面用，不参与 Java 构建。只定义与后端交互的接口，不在前端实现后端：所有路径挂在 `/api/prl/...` 下，由管控后端提供。

从入口 `@potatotv/prl-editor` 导出 React 组件与工具函数；样式单独引入：

```ts
import { Editor, Linter, Debugger, Profiler, RuleVersionManager } from '@potatotv/prl-editor'
import '@potatotv/prl-editor/style.css'
```

组件：`Editor`（源码编辑器，自研高亮 + 补全，不依赖 Monaco/CodeMirror）、`Linter`（本地检查 + 服务端分析）、`Debugger`、`Profiler`、`RuleVersionManager`（版本发布）、以及 `Badge`/`Panel`/`EmptyState`/`InlineError`/`DemoMark` 若干 UI 件。完整 props、`api.ts` 的接口封装与 `prlLanguage` 的关键字/函数表见 [嵌入与集成示例](embedding-examples.md)。