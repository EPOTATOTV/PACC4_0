# 宿主接入指南

宿主（PACC 客户端）通过 `sandbox/PrlHostContext.java` 向规则引擎注册能力。规则里出现的每个函数名，先查宿主白名单，宿主没接管的交给标准库兜底；两条路都不通就是编译期或运行期拒绝。宿主对象的成员访问也走这里，因为沙箱明确禁用了反射。

## 必须实现的两个方法

`PrlHostContext` 只有两个抽象方法：

```java
public interface PrlHostContext {

    /** 调用一个由宿主接管的白名单函数。函数不在白名单内时抛 PrlSecurityException。 */
    Object callFunction(String name, Object[] args);

    /** 宿主接管的函数白名单。同时是编译期白名单的来源。 */
    Set<String> getAvailableFunctions();
}
```

`getAvailableFunctions()` 返回的名字会与标准库签名表取并集，交给类型检查器。白名单为空时视为「宿主没接管任何函数」，此时不设限，全部走标准库与运行期兜底。

不想接管任何函数，直接用 `PrlHostContext.EMPTY`：所有调用抛 `PrlSecurityException`，标准库照常工作，副作用（告警、证据、日志）不会回到宿主。

## 可选能力（都有默认实现）

| 方法 | 默认行为 | 对应规则写法 |
|---|---|---|
| `getMember(Object target, String member)` | 委托 `PrlHostObject`；另外放行 `Map` | `player.name` |
| `callMethod(Object target, String method, Object[] args)` | 委托 `PrlHostObject` | `pattern.is_human_like()` |
| `acceptAlert(DetectionResult alert)` | 什么都不做 | `emit_alert(...)` |
| `recordEvidence(String key, Object value)` | 什么都不做 | `record_evidence(...)` |
| `triggerRedScreen(String reason)` | 什么都不做 | `trigger_redscreen(...)` |
| `log(String level, String message)` | 打到标准输出 | `log(...)` |
| `disableFeature(String feature, long durationMillis)` | 什么都不做 | `ban_feature(...)`，禁用的是客户端功能开关，不是账号 |
| `getTypes()` | 返回 `HostTypeRegistry.standard()` | 规则里写的上下文类型 |

不实现 `acceptAlert` 时，`emit_alert` 的结果只作为返回值回到规则里，宿主侧拿不到。

`getMember` 的默认实现放行 `Map`，所以规则里把 map 当结构体用（`e["damage"]` 与 `e.damage` 等价）不需要宿主写代码。

## 注册上下文类型

规则里不能定义类型，能用的上下文类型全部由宿主注册。`HostTypeRegistry` 负责把源码里的类型引用解析成 `PrlType`。

```java
HostTypeRegistry registry = HostTypeRegistry.standard()
        .register(HostType.builder("ServerState")
                .field("tps", PrlType.FLOAT)
                .field("online_players", PrlType.INT)
                .method("network_stable", PrlType.BOOL)
                .build());

PrlHostContext host = new PrlHostContext() {
    @Override
    public Object callFunction(String name, Object[] args) {
        return myFunctions.get(name).apply(args);
    }

    @Override
    public Set<String> getAvailableFunctions() {
        return myFunctions.keySet();
    }

    @Override
    public HostTypeRegistry getTypes() {
        return registry;
    }
};
```

`HostType.builder(...).field(name, type).method(name, returnType).build()` 里的字段用 `obj.field` 访问，方法用 `obj.method()` 访问。宿主对象在规则里是只读的，没有注册写入口。

`HostTypeRegistry.resolve` 内置了基础类型与集合类型（`int`/`float`/`bool`/`string`/`list[...]`/`set[...]`/`map[..., ...]`/`tuple[...]`），未注册的类型名会抛 `TypeException`。

### 内置的默认类型

`HostTypeRegistry.standard()` 注册了这批类型，脱离 PACC 时也能通过类型检查。

| 类型 | 字段 | 方法 |
|---|---|---|
| `PlayerContext` | `id`、`name`、`platform`（string），`reputation`（int） | `is_trusted() -> bool` |
| `AttackEvent` | `target_changed`、`is_critical`（bool），`damage`、`reach`（float），`target_id`（string），`time_ms`（int） | |
| `MoveEvent` | `speed`、`direction`（float），`duration_ms`（int） | `is_airborne()`、`is_grounded()`、`is_horizontal()` |
| `MiningEvent` | `exposed`（bool），`path_straightness`、`distance_to_vein`（float），`duration_ms`（int） | `is_ore()` |
| `ClickEvent` | `interval_ms`、`position_x`、`position_y`、`position_z`（float），`time_ms`（int），`button`（string） | |
| `MouseMoveEvent` | `delta_yaw`、`delta_pitch`、`precision`（float），`time_ms`（int） | `is_horizontal()` |
| `ClickPattern` | `cps`、`interval_stddev`（float） | `is_human_like()`、`to_json()` |
| `Stats` | `mean`、`stddev`、`median`（float），`count`（int） | |
| `SystemState` | `tps`、`memory_used_mb`（float），`tick`、`online_players`（int） | `network_stable()` |
| `DetectionResult` | `type`、`rule_name`（string），`confidence`（float），`evidence`（map），`timestamp_ms`（int） | |

零参宿主方法允许省略括号：`player.is_trusted` 与 `player.is_trusted()` 等价。

## 运行时值

宿主传给 `callFunction` 的实参、以及规则从宿主对象读到的值，都是下面这些 Java 类型：

| PRL 类型 | Java 表示 |
|---|---|
| `int` | `Long` |
| `float` | `Double` |
| `bool` | `Boolean` |
| `string` | `String` |
| `list[T]` | `java.util.List` |
| `map[K, V]` | `java.util.Map` |
| `set[T]` | `java.util.Set` |
| `tuple[...]` | 不可变的 `List` |
| 宿主对象 | 任意对象，成员的读取得自己接（见下） |

`input` 块里的每个字段按名字从传入的 map 取值，所以 `RuleManager.executeAll` 收到的 map 键就是 `input` 里的字段名。

## 宿主对象协议

沙箱不允许反射，成员读取得由宿主对象自己交出入口。实现 `PrlHostObject` 是最省事的做法：

```java
public final class PlayerContextValue implements PrlHostObject {

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "id" -> id;
            case "name" -> name;
            case "platform" -> platform;
            case "reputation" -> reputation;
            default -> throw new PrlSecurityException("PlayerContext 没有成员 '" + member + "'");
        };
    }

    // 零参方法也走这里；没有方法的对象可以不给这个实现
    @Override
    public Object callMethod(String method, Object[] args) {
        if ("is_trusted".equals(method)) {
            return reputation >= 80;
        }
        throw new PrlSecurityException("PlayerContext 没有方法 '" + method + "'");
    }
}
```

宿主想自己控管成员访问，也可以不实现 `PrlHostObject`，直接覆写 `PrlHostContext.getMember` / `callMethod`。

`Stats` 与 `ClickPattern` 由标准库返回，本模块自带的实现就实现了 `PrlHostObject`，规则脱离 PACC 也能跑。

## 沙箱边界

宿主接入时不用自己写限制，VM 会拦：

| 限制 | 值 |
|---|---|
| 单条规则指令数 | 100000（`PrlVm.MAX_INSTRUCTIONS`） |
| 单条规则执行时间 | 100ms（`PrlVm.TIMEOUT_NANOS`） |
| 调用栈深度 | 64（`PrlVm.MAX_CALL_DEPTH`） |
| 集合元素数 | 10000（`PrlVm.MAX_COLLECTION_SIZE`，目前用于 `a..b` 这类构造） |

超出限制抛 `PrlExecutionException` / `PrlTimeoutException`。规则抛出的任何运行时异常都会被包成 `PrlExecutionException` 返回，不会把宿主进程带崩。

`RuleManager` 还会按规则累计失败次数，连续失败超过 `MAX_FAILURES`（10）就自动禁用该规则。

静态分析器另有一份固定名单，写这些名字会报安全审计（`PRL-S`）：

- 动态执行与进程：`loadstring`、`require`、`eval`、`exec`、`system`、`shell`、`popen`
- 文件：`file_read`、`file_write`、`file_delete`、`open_file`
- 网络：`http_get`、`http_post`、`socket`、`connect`、`dns_query`
- 环境与反射：`getenv`、`setenv`、`class_for_name`、`new_instance`、`os_clock`、`os_time`

成员访问的禁止名单：`getclass`、`class`、`classloader`、`forname`、`newinstance`、`runtime`、`exec`、`exit`。

## 接入规则引擎

```java
RuleManager manager = new RuleManager(host);        // 编译与虚拟机共用同一份宿主
manager.loadRule("kill_aura", source);              // 编译失败抛异常，旧规则原样保留
List<DetectionResult> alerts = manager.executeAll(input);
```

热加载器必须用管理器的宿主编译，否则白名单与运行期不一致：

```java
try (RuleHotLoader loader = new RuleHotLoader(Path.of("rules"), manager)) {
    loader.start();
}
```

热加载以文件名（去掉 `.prl`）作为规则名，内容指纹用 SHA-256 去重，编译失败不卸载旧规则，错误记在 `loader.errors()` 并写日志。