# PACC Rule Language

PRL 是 PACC 玩家端反作弊系统使用的检测规则 DSL。本模块是它的完整实现：词法分析、语法分析、强类型检查、IR 与 `.prlc` 字节码编译、寄存器式虚拟机、多层沙箱、标准库、规则热加载与版本管理、静态分析，以及调试器与 Profiler。

实现只用 JDK 标准库，运行时不依赖任何第三方库。`maven-enforcer-plugin` 会在构建期拦下往主代码里新增的 compile / runtime 依赖。

## 边界

PRL 只做本地检测、留痕和红屏强制警告。规则里没有封禁玩家的能力；`ban_feature` 禁用的是一个客户端功能开关，不是账号。

## 环境

- JDK 21
- Maven 3.9 及以上

## 构建与测试

```bash
mvn -B -ntp test        # 跑单元测试
mvn -B -ntp verify      # 完整校验：enforcer 依赖检查 + 单元测试
mvn -B -ntp package     # 产出 target/pacc-rule-language-1.0.0.jar
```

## Maven 坐标

```xml
<dependency>
    <groupId>com.potatotv</groupId>
    <artifactId>pacc-rule-language</artifactId>
    <version>1.0.0</version>
</dependency>
```

版本号独立于 PACC 产品版本走，主项目按精确版本锁定。

## 用起来

编译一条规则：

```java
PrlHostContext host = PrlHostContext.EMPTY;   // 宿主没接管的函数交给标准库
PrlCompiler compiler = new PrlCompiler(host);
CompileResult result = compiler.compileChecked(source);
if (!result.ok()) {
    System.out.println(result.describe());     // 诊断带行列号
}
```

装进规则管理器再执行：

```java
RuleManager manager = new RuleManager(host);
manager.loadRule("kill_aura", source);

Map<String, Object> input = Map.of(
        "player", playerContext,
        "attack_events", attackEvents,
        "system_state", systemState);
for (DetectionResult alert : manager.executeAll(input)) {
    System.out.println(alert.type() + " " + alert.confidence());
}
```

看守一个规则目录，文件改了自动重编（编译失败会保留旧规则）：

```java
try (RuleHotLoader loader = new RuleHotLoader(Path.of("rules"), manager)) {
    loader.start();
}
```

`examples/` 下有五个能直接编译的规则：[kill_aura.prl](examples/kill_aura.prl)、[speed_hack.prl](examples/speed_hack.prl)、[fly.prl](examples/fly.prl)、[autoclicker.prl](examples/autoclicker.prl)、[xray.prl](examples/xray.prl)。

## 包结构

| 包 | 职责 |
|---|---|
| `lexer` | 词法分析，产出 `Token` |
| `parser` | 递归下降语法分析，产出 AST |
| `ast` | AST 节点（`RuleFile`、`Rule`、`Expression`、`Statement` 等） |
| `types` | 类型模型、内置函数签名表、宿主类型注册表 |
| `check` | 类型检查与诊断 |
| `ir` | 三地址码 IR 生成 |
| `bytecode` | IR 到 `.prlc` 字节码，以及文件读写 |
| `vm` | 寄存器式字节码解释器 |
| `runtime` | 运行时值、宿主对象、运行时异常 |
| `sandbox` | 宿主上下文接口与沙箱边界 |
| `stdlib` | 标准库实现 |
| `compiler` | 编译门面 `PrlCompiler` |
| `engine` | 规则管理、热加载、版本与灰度 |
| `analysis` | 静态分析：空指针、死循环、安全、冲突、复杂度 |
| `debugger` | 调试器：断点、条件断点、日志点、单步、调用栈 |
| `profiler` | 性能分析：执行耗时分布与热点函数 |

入口 API 是 `com.potatotv.prl.Prl`（编译、执行、取引擎/分析器）；上面的类可以单独构造，用它只是为了少写几行。

## 文档

- [语言规范](docs/language-spec.md)
- [标准库参考](docs/stdlib-reference.md)
- [宿主接入指南](docs/host-api-guide.md)
- [从 LuaJ 迁移](docs/migration-from-luaj.md)

## 许可证

见 [LICENSE](LICENSE)。