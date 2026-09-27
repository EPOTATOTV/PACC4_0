# 嵌入与集成示例

这份文档给的是「把 PRL 接进一个 Java 服务」和「把 `prl-editor` 接进管理端」的可运行骨架。签名与真实源码一致，示例为了短会省略错误处理的完整性，别照抄进生产。

语言本身怎么写看 [语言规范](language-spec.md)，宿主怎么注册能力看 [宿主接入指南](host-api-guide.md)，类与方法的完整清单看 [API 参考](api-reference.md)。

## 1. 在 Java 服务里嵌入 PRL

最省事的是走 `RuleManager`：一次编译、长期驻留、批量执行。

```java
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.sandbox.PrlHostContext;

import java.util.List;
import java.util.Map;

public final class DetectionService {

    private final RuleManager manager;

    public DetectionService(PrlHostContext host) {
        // 编译与虚拟机共用同一份宿主，白名单在编译期与运行期才一致
        this.manager = new RuleManager(host);
    }

    /** 装载或替换一条规则；编译失败会抛异常，同名旧规则原样保留。 */
    public void install(String ruleName, String source) {
        manager.loadRule(ruleName, source);
    }

    /** 跑一轮，收集全部告警。单条规则失败不影响其余规则。 */
    public List<DetectionResult> detect(Map<String, Object> input) {
        return manager.executeAll(input);
    }
}
```

`input` 的键就是规则 `input:` 块里的字段名。`executeAll` 按规则名字典序执行，返回的每个 `DetectionResult` 已经由引擎补上了 `ruleName`。

单次验证、不想留状态时用门面即可：

```java
import com.potatotv.prl.Prl;

Object alert = Prl.run(source, Map.of("player", playerContext, "attack_events", events));
```

`run` 返回 `emit_alert` 的产物，没有告警时返回 `null`。

想拿到「编译失败时逐条诊断」而不是异常，用 `compileChecked`：

```java
import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;

PrlCompiler compiler = new PrlCompiler(host);
CompileResult result = compiler.compileChecked(source);
if (!result.ok()) {
    for (var diagnostic : result.errors()) {
        System.out.println(diagnostic.line() + ":" + diagnostic.col() + " " + diagnostic.message());
    }
}
```

开发期想让规则目录边改边生效：

```java
import com.potatotv.prl.engine.RuleHotLoader;

import java.nio.file.Path;

try (RuleHotLoader loader = new RuleHotLoader(Path.of("rules"), manager)) {
    loader.start();
    // ... 服务运行期间
    loader.errors().forEach((rule, error) -> System.out.println(rule + " 编译失败：" + error));
}
```

热加载器必须用管理器的宿主编译，否则白名单检查与运行期会对不上。

## 2. 自定义 `PrlHostContext`

必须实现 `callFunction` 与 `getAvailableFunctions`。**关键约束**：`getAvailableFunctions()` 返回的是「允许被调用的函数全集」，标准库函数也必须列进去；只要函数名进了白名单，运行期就会先落到 `callFunction`，所以标准库名字要自己转交回 `PrlStdlib`。

```java
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.stdlib.PrlStdlib;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class PaccHost implements PrlHostContext {

    private final Map<String, Function<Object[], Object>> custom;
    private final PrlStdlib stdlib = new PrlStdlib(this);
    private final List<DetectionResult> alerts = new ArrayList<>();

    public PaccHost(Map<String, Function<Object[], Object>> custom) {
        this.custom = custom;
    }

    @Override
    public Set<String> getAvailableFunctions() {
        // 标准库名字必须一起声明，宿主自己的函数再加进来
        Set<String> names = new LinkedHashSet<>(PrlStdlib.functionNames());
        names.addAll(custom.keySet());
        return names;
    }

    @Override
    public Object callFunction(String name, Object[] args) {
        Function<Object[], Object> mine = custom.get(name);
        if (mine != null) {
            return mine.apply(args);
        }
        if (PrlStdlib.supports(name)) {
            return stdlib.call(name, args);   // 声明过的标准库名字要自己转交回去
        }
        throw new PrlSecurityException("宿主没有注册函数 '" + name + "'");
    }

    // 副作用通过 default 方法交还给宿主；不覆写的话告警落不到宿主侧
    @Override
    public void acceptAlert(DetectionResult alert) {
        alerts.add(alert);
    }

    public List<DetectionResult> alerts() {
        return List.copyOf(alerts);
    }
}
```

宿主对象的成员访问走 `PrlHostObject`（沙箱禁反射）。实现它，规则里的 `player.name`、`pattern.is_human_like()` 才有落点：

```java
import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;

public final class PlayerContextValue implements PrlHostObject {

    private final String id;
    private final String name;
    private final int reputation;

    public PlayerContextValue(String id, String name, int reputation) {
        this.id = id;
        this.name = name;
        this.reputation = reputation;
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "id" -> id;
            case "name" -> name;
            case "platform" -> "bedrock";
            case "reputation" -> (long) reputation;   // int 在运行期是 Long
            default -> throw new PrlSecurityException("PlayerContext 没有成员 '" + member + "'");
        };
    }

    @Override
    public Object callMethod(String method, Object[] args) {
        if ("is_trusted".equals(method)) {
            return reputation >= 80;
        }
        throw new PrlSecurityException("PlayerContext 没有方法 '" + method + "'");
    }
}
```

注册自定义上下文类型，规则里才能用自己声明的类型名：

```java
import com.potatotv.prl.types.HostType;
import com.potatotv.prl.types.HostTypeRegistry;
import com.potatotv.prl.types.PrlType;

HostTypeRegistry registry = HostTypeRegistry.standard()
        .register(HostType.builder("ServerState")
                .field("tps", PrlType.FLOAT)
                .field("online_players", PrlType.INT)
                .method("network_stable", PrlType.BOOL)
                .build());
```

然后在 `PaccHost` 里覆写 `getTypes()` 返回 `registry` 即可。

## 3. 静态检查接入

管理端保存前先做静态分析：它把编译诊断、空指针、无限循环、安全审计、规则冲突、复杂度一起给出来。

```java
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.analysis.PrlAnalyzer;
import com.potatotv.prl.analysis.RuleMetrics;

PrlAnalyzer analyzer = new PrlAnalyzer(host);
AnalysisResult analysis = analyzer.analyze(source);

if (!analysis.ok()) {
    System.out.println(analysis.report());   // 含错误与冲突
}
analysis.metrics("kill_aura").ifPresent(metrics -> {
    if (metrics.complex()) {
        System.out.println("复杂度偏高：" + metrics.complexityScore());
    }
});
```

冲突不拦发布，但值得在界面上标出来：

```java
for (var conflict : analysis.conflicts()) {
    System.out.println(conflict.ruleA() + " vs " + conflict.ruleB() + "：" + conflict.reason());
}
```

## 4. 调试器接入

`PrlDebugger` 是两线程模型：被调试的规则在后台线程跑，命中暂停条件时阻塞；控制线程取现场、放行。断点粒度是源码行。

```java
import com.potatotv.prl.debugger.DebugState;
import com.potatotv.prl.debugger.PrlDebugger;

import java.util.concurrent.TimeUnit;

PrlDebugger debugger = new PrlDebugger(host);
debugger.setBreakpoint(12);
debugger.addLogpoint(20);

debugger.start(bytecode, "kill_aura", input);

// 等它停在第 12 行
debugger.awaitPause(2, TimeUnit.SECONDS).ifPresent(state -> {
    System.out.println(state.functionName() + " 第 " + state.line() + " 行");
    state.variables().forEach((name, value) -> System.out.println("  " + name + " = " + value));
    System.out.println("调用栈：" + state.callStack());
});

debugger.stepOver();                       // 单步
debugger.awaitPause(2, TimeUnit.SECONDS);  // 等下一处
debugger.resume();                         // 放行到下一个断点
Object result = debugger.awaitFinish(5, TimeUnit.SECONDS);
debugger.close();
```

条件断点的条件是 `Predicate<PrlFrameView>`，不是 PRL 源码字符串——要编译一段 PRL 表达式得知道 `input` 里每个字段的宿主类型，那份信息只有管理端编辑器手上才有：

```java
debugger.setBreakpoint(12, frame -> {
    Object events = frame.variables().get("events");
    return events instanceof java.util.List<?> list && list.size() > 50;
});
```

日志点在后台线程被调用，`sink` 里别做重活：

```java
debugger.addLogpoint(20, record ->
        System.out.println("L" + record.line() + " " + record.variables()));
```

## 5. 性能分析接入

`PrlProfiler` 挂在 `RuleManager` 的观察者上，随规则执行自动采集。

```java
import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.profiler.PrlProfiler;
import com.potatotv.prl.profiler.ProfilerReport;

PrlProfiler profiler = new PrlProfiler();
RuleManager manager = new RuleManager(host, profiler);

profiler.register("kill_aura", "1.3.0");
manager.loadRule("kill_aura", source);
manager.executeAll(input);   // 采集发生在执行期间

ProfilerReport report = profiler.report("kill_aura");
System.out.println(report.text());
for (var hotspot : report.hotspots()) {
    System.out.printf("%s 占 %.0f%%（%d 次调用）%n",
            hotspot.name(), hotspot.share() * 100, hotspot.calls());
}
```

实测内存占用可以喂进去取峰值：

```java
profiler.markMemory("kill_aura", runtimeUsedBytes);
```

Profiler 采的是墙钟时间，含调度与 GC 抖动，用来判断「哪个函数占大头」够用，别当微基准。

## 6. 管理端用 `prl-editor`（React）

`prl-editor` 在 `pacc-rule-language/prl-editor`，包名 `@potatotv/prl-editor`，是 React 组件包（peer 依赖 React 18）。组件不自己注入 CSS，样式要单独引一次。

```tsx
import { useCallback, useState } from 'react'
import { Editor, Linter } from '@potatotv/prl-editor'
import type { Diagnostic } from '@potatotv/prl-editor'
import '@potatotv/prl-editor/style.css'

export function RuleWorkbench() {
  const [source, setSource] = useState('rule "kill_aura" {\n}\n')
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([])
  const [activeLine, setActiveLine] = useState(1)

  // onDiagnostics 每次回调都是新数组，直接用 setState 会打穿下游依赖，稳定一下引用
  const handleDiagnostics = useCallback((next: Diagnostic[]) => setDiagnostics(next), [])

  return (
    <div className="workbench">
      <Editor
        value={source}
        onChange={setSource}
        activeLine={activeLine}
        onActiveLineChange={setActiveLine}
        diagnostics={diagnostics}
        minLines={16}
      />
      <Linter
        source={source}
        activeLine={activeLine}
        onSelectLine={(line) => setActiveLine(line)}
        onDiagnostics={handleDiagnostics}
        baseUrl="/api/prl"
        ruleName="kill_aura"
      />
    </div>
  )
}
```

`Editor` 的主要 props：`value`、`onChange`、`onSave`（Ctrl/Cmd+S）、`readOnly`、`placeholder`、`diagnostics`、`activeLine`、`onActiveLineChange`、`revealLine`（`{ line, seq }`，`seq` 变化就跳一次）、`hostFunctions`（宿主注册的函数名，参与补全）、`minLines`、`className`。

`Linter` 同时跑本地检查与（可选的）服务端分析。它继承 `PrlEndpointProps`（`baseUrl`、`fetcher`、`requestTimeoutMs`），另加：`source`、`activeLine`、`onSelectLine`、`hostFunctions`、`ruleName`、`serverThrottleMs`（默认 800ms）、`disableServerAnalysis`、`onDiagnostics`。不传 `baseUrl` / `fetcher` 时只跑本地检查，界面会如实说明，不假装有服务端结论。

要注入带鉴权的请求，传 `fetcher` 而不是改全局 fetch：

```tsx
import type { PrlFetcher } from '@potatotv/prl-editor'

const fetcher: PrlFetcher = (url, init) =>
  fetch(url, { ...init, headers: { ...init?.headers, Authorization: `Bearer ${token}` } })

<Linter source={source} baseUrl="/api/prl" fetcher={fetcher} />
```

调试、性能分析、版本管理三个面板用法类似。它们都接受受控 props：`Debugger` 的 `breakpoints` / `onBreakpointsChange` / `state` / `onAction`，`Profiler` 的 `profile`（直接注入结果就不再请求后端），`RuleVersionManager` 的 `versions` / `onAction` / `onSelect`。给了 `onAction` 后组件不再调用内置接口，完全由宿主接管。

```tsx
import { Profiler, RuleVersionManager } from '@potatotv/prl-editor'

<Profiler ruleName="kill_aura" ruleVersion="1.3.0" window="24h" baseUrl="/api/prl" />

<RuleVersionManager
  ruleName="kill_aura"
  source={source}
  author="pacc-dev"
  approver="admin"
  canaryPercent={1}
  baseUrl="/api/prl"
  onAction={async (action, version, payload) => {
    await myBackend.apply(action, version, payload)
  }}
/>
```

`api.ts` 里的接口封装与后端约定（都挂在 `/api/prl` 下）：`POST /analyze`、`POST /debug/session`、`POST /debug/step`、`POST /debug/resume`、`POST /debug/stop`、`PUT /debug/breakpoints`、`GET /profiles/{ruleName}`、`GET /versions`、`POST /versions`、`POST /versions/{ruleName}/{version}/canary`、`POST /versions/{ruleName}/{version}/approve`、`POST /versions/{ruleName}/rollback`。这些接口由管控后端提供，不在本包内实现；后端缺席时组件走空态 + 明确标记的演示数据，不冒充真实结果。