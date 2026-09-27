# 从 LuaJ 迁移

PACC 早先的检测脚本用 LuaJ 跑。PRL 换掉了整条执行链：静态类型、无全局状态、无运行期求值、函数白名单、多层沙箱。迁移不是逐行翻译，多数脚本要按规则结构重写一遍。

## 先看差别

- **静态类型**。`input` 里每个变量都要写类型；`let` 的类型可以推断，也可以标注。没有隐式数值转换，`int` 与 `float` 混用要显式 `to_float` / `to_int` / `to_string`。
- **没有具名函数定义**。规则里只能写 lambda（`x -> expr`），或者调标准库与宿主注册的函数。公共逻辑靠宿主注册函数，别指望在规则文件里复用代码。
- **没有全局状态、没有模块、没有 `require`**。一条规则文件只描述一条（或几条）`rule`。
- **函数白名单**。名字不在签名表里，编译期就报错，不会留到运行期。
- **沙箱禁了一批名字**。`loadstring`、`require`、`eval`、`exec`、`system`、`os_clock`、`os_time`，以及文件、网络、反射相关的调用，都会报安全审计（诊断码 `PRL-S`）。这些在 LuaJ 时代能干的事，PRL 里没有对应写法。
- **注释是 `#`，不是 `--`**。`--` 在 PRL 里会被当成减号，直接报错。
- **`..` 不是字符串拼接**。PRL 里 `a .. b` 是区间（`RangeExpr`），字符串拼接用 `+`。
- **没有 `break` / `continue` / `repeat` / `elseif`**。`elseif` 写成 `else if`；循环提前退出要靠条件收窄，或者换用集合函数。
- **逻辑运算符大小写都收**。`and` / `or` / `not` 与 `AND` / `OR` / `NOT` 等价，Lua 风格的写法能直接保留。

## 写法对照

| LuaJ | PRL | 说明 |
|---|---|---|
| `-- 注释` | `# 注释` | |
| `nil` | `null` | |
| `a ~= b` | `a != b` | |
| `a .. b` | `a + b` | PRL 的 `..` 是区间 |
| `#t` | `count(t)` | 字符串长度用 `length(s)` |
| `if rate > 8 and accuracy > 0.95 then ... end` | `when: rate > 8 AND accuracy > 0.95`，动作放 `then:` | 条件与动作分到 `when` / `then` 两段 |
| `for i = 1, #t do ... t[i] ... end` | `for item in t: ... end` | `list` / `tuple` 按位置迭代 |
| `for k, v in pairs(m) do ... end` | `for k in m: ... end` | 迭代 `map` 只给键，取值用 `m[k]` |
| `for i, c in ipairs(s) do`（字符串） | `for c in s: ... end` | 字符串逐字符迭代 |
| `table.insert(t, x)` | `t = t + [x]` | PRL 用列表拼接，没有原地 append |
| `table.remove(t)` | `take` / `skip` / `filter` 重组 | 没有原地删除 |
| `string.match(s, p)` | `regex_match(s, p)` | 要取匹配内容用 `regex_extract` |
| `string.find(s, p)` | `contains(s, p)` 或 `regex_match` | |
| `string.lower(s)` / `string.upper(s)` | `to_lower(s)` / `to_upper(s)` | |
| `string.len(s)` | `length(s)` | |
| `string.sub(s, i, j)` | `split` / `regex_extract` 重组 | 没有直接对应 |
| `tostring(x)` | `to_string(x)` | |
| `tonumber(x)` | `to_int(x)` / `to_float(x)` | 按目标类型选 |
| `math.floor(x)` / `math.ceil(x)` / `math.sqrt(x)` | `floor(x)` / `ceil(x)` / `sqrt(x)` | |
| `os.clock()` / `os.time()` | 无 | 沙箱禁止 |
| `loadstring(code)` / `dofile(p)` | 无 | 沙箱禁止 |
| `require("module")` | 无 | 沙箱禁止 |
| `pcall(f)` | 无 | 规则抛出的异常由引擎隔离，写法上不需要包一层 |

函数清单见[标准库参考](stdlib-reference.md)，那里也写了各函数的口径（比如 `stddev` 是总体口径、空表的返回约定）。

## 迁移步骤

1. **拆规则**。一条 LuaJ 脚本如果同时管好几种作弊，按告警类型拆成多个 `rule`，每条只判一类。规则之间没有依赖，名字按字典序执行。
2. **补类型声明**。把脚本读到的输入整理成 `input` 块，逐个标类型。这一步最容易暴露原来的隐式假设：Lua 里同一个变量一会儿当数字一会儿当字符串，到了 PRL 必须定下来。
3. **换函数**。按上面的对照表替换；替换完如果名字不在签名表里，编译期就会报错，照着错误逐个改。
4. **补齐元数据**。`version`、`author`、`severity`、`category`、`cooldown`、`enabled`，以及可选的 `description`。
5. **跑静态分析**。`new PrlAnalyzer(host).analyze(source)` 会给空指针（`PRL-N`）、可证明的无限循环（`PRL-L`）、安全审计（`PRL-S`）、规则冲突（`PRL-C`）和复杂度 / 性能预估。发布前把错误清掉。

## 一个例子

LuaJ 里常见的一段（同一段读取速率与命中率，命中就告警）：

```lua
-- 旧写法，仅示意
local rate = 0
for i = 1, #attack_events do
  local e = attack_events[i]
  if e.damage > 0 then hits = hits + 1 end
end
if rate > 8 and accuracy > 0.95 then
  alert("KILL_AURA", 0.92, {})
end
```

到 PRL 里改成一条完整规则：

```
rule "kill_aura_detection" {
    version: "1.2.0"
    author: "PACC Security Team"
    severity: high
    category: "combat"
    cooldown: 300s
    enabled: true

    input {
        player: PlayerContext
        attack_events: list[AttackEvent]
        system_state: SystemState
    }

    let attack_rate = rate(attack_events, 1s)
    let hit_accuracy = accuracy(attack_events)

    when:
        attack_rate > 8 per_second
        AND hit_accuracy > 0.95
        AND NOT player.is_trusted()

    then:
        emit_alert(
            type = "KILL_AURA",
            confidence = 0.92,
            evidence = {
                "attack_rate": attack_rate,
                "hit_accuracy": hit_accuracy
            }
        )
}
```

差别集中在三处：循环统计换成了 `rate` / `accuracy`，条件进了 `when`，告警进了 `then`。`examples/` 下的五个规则都是这种结构，可以直接拿来当模板。