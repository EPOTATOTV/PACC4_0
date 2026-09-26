# PRL 语言规范

这份规范按当前实现写：词法见 `lexer/TokenType.java`、`lexer/Lexer.java`，语法见 `parser/Parser.java`，AST 见 `ast/`。规范里出现的写法都能被这套实现解析、编译。

## 1. 源文件

- 一个 `.prl` 文件里放一条或多条 `rule` 块。
- 注释以 `#` 开头，一直到行尾。
- 空格、制表、换行、分号在语法阶段就被丢掉，不承载语义。语句之间不需要分隔符。
- 标识符：字母或 `_` 开头，后面跟字母、数字、`_`。

## 2. 关键字

| 分类 | 关键字 |
|---|---|
| 结构 | `rule` `input` `let` `when` `then` `else` `end` `if` `for` `while` `return` `in` |
| 逻辑 | `and` `or` `not` `true` `false` `null` |
| 类型名 | `int` `float` `bool` `string` `list` `map` `set` `tuple` |
| 严重级 | `low` `medium` `high` `critical` |
| 元数据 | `version` `author` `severity` `category` `cooldown` `enabled` `description` |
| 动作 | `emit_alert` `record_evidence` `trigger_redscreen` `log` `ban_feature` |
| 单位 | `per_second` `per_minute` `ms` `s` `m` `h` `kb` `mb` `gb` |

词法阶段把标识符统一转成小写再查关键字表，所以源码里写 `RULE`、`When`、`TRUE` 也能识别。约定上全小写书写；`and` / `or` / `not` 大小写不敏感是特意留的，方便从 Lua 迁过来的脚本直接用。

表达式位置出现的类型名与元数据关键字按普通标识符处理。这是为了让 `map(values, f)`、`emit_alert(...)`、`let severity = "high"` 这类写法能工作 —— 类型引用只出现在 `input` 与 `let` 的类型标注位置。

## 3. 字面量

| 字面量 | 写法 | 说明 |
|---|---|---|
| 整数 | `42`、`0xFF`、`0b1010`、`1_000_000` | 十进制、`0x` 十六进制、`0b` 二进制，可带 `_` 分隔符。运行时是 64 位有符号整数 |
| 浮点 | `1.5`、`1e3`、`1.5E-2` | 小数点后必须跟数字，`1.` 不算浮点；指数是 `e` / `E` |
| 字符串 | `"text"` 或 `'text'` | 单双引号都行 |
| 转义 | `\n` `\t` `\r` `\\` `\"` `\'` `\uXXXX` | 其他转义序列报词法错误 |
| f-string | `f"player: {player.name}"` | `f` 大小写均可；`{表达式}` 插值，`{{` 与 `}}` 表示字面花括号 |
| 布尔 | `true` / `false` | |
| 空 | `null` | |
| 严重级 | `low` `medium` `high` `critical` | 可直接当表达式使用 |
| 带单位 | `300s`、`8 per_second`、`10MB`、`300 s` | 见下 |

字符串与 f-string 都不能跨行。

### 带单位字面量

数值后跟单位，紧贴（`300s`）或空格分开（`300 s`）都行，单位大小写不敏感。单位三类：

| 类别 | 单位 | 归一化 |
|---|---|---|
| 时间 | `ms` `s` `m` `h` | 毫秒 |
| 数据 | `kb` `mb` `gb` | 字节，1024 进制 |
| 速率 | `per_second` `per_minute` | 每秒次数 |

所以 `rate(attack_events, 1s)` 传进去的是 `1000`，`8 per_second` 就是数值 `8`，`10MB` 是 `10485760`。

## 4. 运算符与优先级

从低到高：

| 优先级 | 运算符 | 结合性 |
|---|---|---|
| 1 | `\|>` 管道 | 左 |
| 2 | `or` | 左 |
| 3 | `??` 空合并 | 左 |
| 4 | `and` | 左 |
| 5 | `not`（一元） | |
| 6 | `==` `!=` `>` `<` `>=` `<=` `in` `not in` | 左 |
| 7 | `..` 区间 | |
| 8 | `+` `-` | 左 |
| 9 | `*` `/` `%` | 左 |
| 10 | `-` `+`（一元） | |
| 11 | `^` 幂 | 右 |
| 12 | `()` `[]` `.` | 后缀 |

`not in` 是词法阶段合成的一个运算符，写 `not in` 就行。

赋值只在语句位置出现，不是运算符：`=`、`+=`、`-=`、`*=`、`/=`。

`+` 在字符串上是拼接，在列表上是拼接，在数值上是加法；`int` 与 `float` 不能混用，`1 / 2` 是整数除法（结果 `0`），要小数得写 `1.0 / 2.0`。

## 5. 类型

| 类型 | 写法 | 说明 |
|---|---|---|
| 整数 | `int` | 64 位有符号 |
| 浮点 | `float` | 双精度 |
| 布尔 | `bool` | |
| 字符串 | `string` | |
| 列表 | `list[T]` | |
| 集合 | `set[T]` | |
| 映射 | `map[K, V]` | |
| 元组 | `tuple[A, B, ...]` | 至少一个元素类型 |
| 宿主类型 | `PlayerContext` 等 | 由宿主注册，见[宿主接入指南](host-api-guide.md) |

`null` 可以赋给引用型（宿主对象、集合、字符串）。

没有隐式数值转换。`int` 与 `float` 之间要显式转：`to_float(x)`、`to_int(x)`、`to_string(x)`。

`number` 与 `any` 只用在标准库签名里，不是能写进源码的类型名。

## 6. 规则结构

```
rule "规则名" {
    <元数据条目>
    input { ... }
    let ...
    when: <布尔表达式>
    then: <语句...>
}
```

规则名必须是字符串字面量。块内各项可以按任意顺序出现，但 `input`、`when`、`then` 各自最多一次。块用 `}` 收尾。

### 6.1 元数据

| 键 | 值 |
|---|---|
| `version` | 字符串 |
| `author` | 字符串 |
| `severity` | `low` / `medium` / `high` / `critical`（不带引号） |
| `category` | 字符串 |
| `cooldown` | 时间单位字面量 |
| `enabled` | `true` / `false` |
| `description` | 字符串 |

不写时的默认：`severity` 为 `low`，`cooldown` 为 `0`，`enabled` 为 `true`。

```
version: "1.0.0"
author: "PACC Security Team"
severity: high
category: "movement"
cooldown: 300s
enabled: true
description: "位移速率持续超阈值"
```

### 6.2 input

```
input {
    player: PlayerContext
    move_events: list[MoveEvent]
    system_state: SystemState
}
```

每行 `名字: 类型`。这些名字在 `when` 与 `then` 里可直接使用，运行时按名字从传入的输入 map 取值。

### 6.3 let

```
let confidence = detect_fly(move_events)
let speed_p95: float = percentile(speeds, 95.0)
```

类型标注可省。`let` 也可以写在 `then:` 块里。

## 7. 语句

| 形式 | 写法 |
|---|---|
| 声明 | `let name = expr`、`let name: Type = expr` |
| 赋值 | `name = expr`、`name += expr`、`name -= expr`、`name *= expr`、`name /= expr` |
| 下标赋值 | `list[i] = expr`、`map[key] = expr` |
| 表达式语句 | 通常用来调动作函数，如 `emit_alert(...)` |
| 条件 | `if cond: ... end`，`if cond: ... else: ... end`，`if cond: ... else if cond2: ... end` |
| for | `for item in iterable: ... end` |
| while | `while cond: ... end` |
| 返回 | `return`、`return expr` |

赋值目标只能是变量或下标；给成员赋值（`player.name = ...`）会报错。

`if` / `for` / `while` 都用 `end` 收尾。`else if` 链共用最后一个 `end`，每个 `else if` 自己不再多写一个 `end`。

没有 `break` 和 `continue`。

`for` 的迭代顺序：`list` / `tuple` 按位置，`set` 按插入顺序，`map` 按键的插入顺序（只给键，取值用 `map[key]`），`string` 逐字符。

## 8. 表达式

### 调用

```
rate(attack_events, 1s)
emit_alert(type = "FLY", confidence = confidence, evidence = { "x": 1 })
```

实参可以写名字（`name = expr`），也可以按位置写。只有命名函数和宿主方法可以被调用 —— 拿一个非函数值加括号调用会报错。

### 成员与下标

```
player.name                  # 成员访问（字段）
player.is_trusted()          # 宿主方法调用
player.is_trusted            # 零参方法可省括号，与上一行等价
click_pattern.to_json()      # 宿主方法
move_events[0]               # 列表下标，越界报运行期错误
text[0]                      # 字符串取单个字符
evidence["speed"]            # 映射按键取值，键不存在给 null
```

### 集合字面量

```
[1, 2, 3]                    # 列表
[]                           # 空列表
{ "k": 1, "j": 2 }           # 映射
{}                           # 空映射
{ 1, 2, 3 }                  # 集合
(a, b)                       # 元组（括号内至少一个逗号）
1..10                        # 区间，左右都含
```

`{...}` 是映射还是集合，看第一个元素后面跟不跟 `:`。

### lambda

```
e -> e.target_changed
(a, b) -> a + b
```

lambda 只作为函数值使用，通常是 `filter` / `map` / `sort` 这类函数的实参。

### 管道

```
attack_events |> filter(e -> e.is_airborne())
```

`left |> f(a)` 等价于 `f(left, a)`，左值作为右侧调用的第一个实参。右侧必须是函数调用或 lambda。

### 表达式形式的 if

```
let level = if score > 0.9: "high" else: "low" end
```

`if` 条件 `:` 值 `else:` 值 `end`，四个部分都要有。

## 9. 完整示例

下面这份是 `examples/speed_hack.prl` 的原文，可以直接编译运行。

```
# speed_hack：移动速度异常（位移速率长期超过人体极限，且方向抖动极低）

rule "speed_hack_detection" {
    version: "1.0.0"
    author: "PACC Security Team"
    severity: critical
    category: "movement"
    cooldown: 600s
    enabled: true
    description: "短时间内水平位移速率持续超阈值，且转向熵偏低"

    input {
        player: PlayerContext
        move_events: list[MoveEvent]
        system_state: SystemState
    }

    let confidence = detect_speed_hack(move_events)
    let horizontal = filter(move_events, e -> e.is_horizontal())
    let speeds = map(horizontal, e -> e.speed)
    let speed_p95 = percentile(speeds, 95.0)
    let direction_entropy = entropy(map(horizontal, e -> e.direction))

    when:
        confidence > 0.85
        AND speed_p95 > 12.0
        AND direction_entropy < 0.4
        AND NOT player.is_trusted()
        AND system_state.network_stable()

    then:
        emit_alert(
            type = "SPEED_HACK",
            confidence = confidence,
            evidence = {
                "speed_p95": speed_p95,
                "direction_entropy": direction_entropy,
                "sample_count": count(horizontal)
            }
        )
        record_evidence("move_timeline", last_n(move_events, 200))
        log(level = "warn", message = f"speed_hack 命中: {player.name} p95={speed_p95}")
        trigger_redscreen(reason = "speed_hack_detected")
}
```

`examples/` 下另外四个（`kill_aura.prl`、`fly.prl`、`autoclicker.prl`、`xray.prl`）用了条件块、`else`、`to_float` 转换等写法，可以对照着看。

## 10. 诊断码

编译与静态分析产出的诊断都带行列号，按码分类：

| 码 | 来源 | 含义 |
|---|---|---|
| `PRL-P` | `PrlCompiler` | 词法 / 语法错误 |
| `PRL-T` | `TypeChecker` | 类型错误与警告 |
| `PRL-N` | `PrlAnalyzer` | 可能的空指针（映射取值、`first` / `last`） |
| `PRL-L` | `PrlAnalyzer` | 可证明的无限循环（`while true:` 且循环体里没有 `return`） |
| `PRL-S` | `PrlAnalyzer` | 安全审计：调用了沙箱禁止的名字 |
| `PRL-C` | `PrlAnalyzer` | 规则冲突：`when` 条件相同但告警类型不同 |

`CompileResult.describe()` 会把全部诊断拼成一段可读文本；`AnalysisResult.ok()` 表示没有错误也没有冲突，可以发布。