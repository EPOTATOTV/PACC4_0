# 标准库参考

标准库是宿主没接管的函数名的兜底实现。函数名与签名以 `types/PrlSignatures.java` 为准，实现见 `stdlib/PrlStdlib.java`。类型检查会拿这张签名表当白名单：表外的函数名在编译期就被拒，不会留到运行期才炸。

宿主可以先接管同名函数。运行期的解析顺序是：本程序的函数表 → 宿主白名单 → 标准库。

## 签名记号

- `number` 匹配 `int` 与 `float`；`any` 匹配一切。
- `T`、`U`、`K`、`A`、`B` 是泛型占位符，同一个函数里同名占位符必须解析成同一类型。
- `Duration` 参数按 `int`（毫秒）登记：写 `300s` 这种单位字面量会先归一化成毫秒。
- `Event` 参数按 `any` 登记，规则不关心具体事件类型。

## 与设计文档字面不同的几处

签名表里做了这些取舍，写规则时按下面这版来：

- `abs`、`min`、`max`、`sum`、`clamp` 拆成 `int` / `float` 两个重载。PRL 没有隐式数值转换，返回值类型必须确定。
- `average`、`median`、`stddev`、`variance`、`percentile` 返回固定是 `float`，入参用 `number` 模式，一个签名同时接受整数表和浮点表。
- `emit_alert` 登记为返回 `DetectionResult`（不是 `void`），规则里当语句用即可，返回值只在宿主侧有意义。
- `analyze_click_pattern` 登记了两个签名（`list[ClickEvent]` 与 `list[AttackEvent]`），两处都能编过。
- `accuracy`、`last_n` 出现在示例规则里但函数表没列，按示例补齐。
- `to_float` / `to_int` / `to_string` 是显式转换，见文末。

## 统计

| 函数 | 签名 | 说明 |
|---|---|---|
| `average` | `average(values: list[number]) -> float` | 平均值；空表给 `0.0` |
| `median` | `median(values: list[number]) -> float` | 中位数 |
| `stddev` | `stddev(values: list[number]) -> float` | 标准差（总体口径，除以 `n`） |
| `variance` | `variance(values: list[number]) -> float` | 方差（总体口径） |
| `min` | `min(values: list[int]) -> int` / `min(values: list[float]) -> float` | 最小值；空表给 `0.0` |
| `max` | `max(values: list[int]) -> int` / `max(values: list[float]) -> float` | 最大值；空表给 `0.0` |
| `sum` | `sum(values: list[int]) -> int` / `sum(values: list[float]) -> float` | 求和；空表给 `0` / `0.0` |
| `percentile` | `percentile(values: list[number], percent: number) -> float` | 百分位数，线性插值；`percent` 必须落在 0~100 |
| `count` | `count(values: list[T]) -> int` | 元素个数 |
| `frequency` | `frequency(values: list[T]) -> map[T, int]` | 频率分布 |
| `correlation` | `correlation(xs: list[number], ys: list[number]) -> float` | 皮尔逊相关系数；任一侧零方差给 `0.0` |
| `zscore` | `zscore(value: number, values: list[number]) -> float` | Z 分数；标准差为 0 时给 `0.0` |
| `outliers` | `outliers(values: list[number], factor: number) -> list[int]` | 异常值下标（IQR 法） |

## 时序

时序函数依赖事件上的 `time_ms` 字段，读不到就按运行期参数错误处理。

| 函数 | 签名 | 说明 |
|---|---|---|
| `rate` | `rate(events: list[Event], window: int) -> float` | 单位时间事件数，固定按秒归一：`(元素数 - 1) * 1000 / 首尾时间差` |
| `sliding_window` | `sliding_window(events: list[T], window: int) -> list[list[T]]` | 以每个事件为起点、宽度 `window` 毫秒的窗口；结果超过 10000 个元素会拒绝 |
| `burst_count` | `burst_count(events: list[Event], window: int, threshold: int) -> int` | 任意 `window` 毫秒内事件数达到 `threshold` 记一次突发，时间重叠的合并 |
| `interval_stats` | `interval_stats(events: list[T]) -> Stats` | 事件间隔统计，返回 `Stats` |
| `trend` | `trend(values: list[number]) -> float` | 最小二乘线性趋势斜率 |
| `change_rate` | `change_rate(values: list[number]) -> float` | 末值相对首值的变化率 |

`Stats` 的成员：`mean`、`stddev`、`median`（都是 `float`）和 `count`（`int`，区间个数）。

## 集合

| 函数 | 签名 | 说明 |
|---|---|---|
| `filter` | `filter(values: list[T], predicate: (T) -> bool) -> list[T]` | 过滤 |
| `map` | `map(values: list[T], transform: (T) -> U) -> list[U]` | 映射 |
| `reduce` | `reduce(values: list[T], initial: U, folder: (U, T) -> U) -> U` | 归约 |
| `sort` | `sort(values: list[T], comparator: (T, T) -> int) -> list[T]` | 排序；第二参不是函数时按默认比较升序。始终返回新列表 |
| `group_by` | `group_by(values: list[T], key: (T) -> K) -> map[K, list[T]]` | 分组 |
| `first` | `first(values: list[T]) -> T` | 第一个元素；空集合是运行期错误 |
| `last` | `last(values: list[T]) -> T` | 最后一个元素；空集合是运行期错误 |
| `take` | `take(values: list[T], n: int) -> list[T]` | 取前 N 个；`n <= 0` 给空表 |
| `skip` | `skip(values: list[T], n: int) -> list[T]` | 跳过前 N 个 |
| `contains` | `contains(values: list[T], value: T) -> bool` | 是否包含元素 |
| `contains` | `contains(text: string, substring: string) -> bool` | 是否包含子串 |
| `unique` | `unique(values: list[T]) -> list[T]` | 去重，保持首次出现的顺序 |
| `flatten` | `flatten(values: list[list[T]]) -> list[T]` | 展平一层 |
| `zip` | `zip(left: list[A], right: list[B]) -> list[tuple[A, B]]` | 拉链；长度取两者较短 |

## 字符串

| 函数 | 签名 | 说明 |
|---|---|---|
| `length` | `length(text: string) -> int` | 长度 |
| `starts_with` | `starts_with(text: string, prefix: string) -> bool` | 前缀匹配 |
| `ends_with` | `ends_with(text: string, suffix: string) -> bool` | 后缀匹配 |
| `regex_match` | `regex_match(text: string, pattern: string) -> bool` | 正则匹配（`find` 语义）；非法正则抛运行期错误 |
| `regex_extract` | `regex_extract(text: string, pattern: string) -> list[string]` | 有捕获组取第 1 组，无捕获组取整个匹配 |
| `to_lower` | `to_lower(text: string) -> string` | 转小写 |
| `to_upper` | `to_upper(text: string) -> string` | 转大写 |
| `trim` | `trim(text: string) -> string` | 去首尾空白 |
| `split` | `split(text: string, separator: string) -> list[string]` | 分割；分隔符为空时按单个字符切 |
| `join` | `join(parts: list[string], separator: string) -> string` | 连接 |
| `levenshtein` | `levenshtein(left: string, right: string) -> int` | 编辑距离 |
| `similarity` | `similarity(left: string, right: string) -> float` | 文本相似度，`1 - 编辑距离 / 较长长度` |

## 数学

| 函数 | 签名 | 说明 |
|---|---|---|
| `abs` | `abs(value: int) -> int` / `abs(value: float) -> float` | 绝对值 |
| `round` | `round(value: float, digits: int) -> float` | 四舍五入到 `digits` 位小数 |
| `floor` | `floor(value: float) -> int` | 向下取整 |
| `ceil` | `ceil(value: float) -> int` | 向上取整 |
| `clamp` | `clamp(value: int, lower: int, upper: int) -> int` / `clamp(value: float, lower: float, upper: float) -> float` | 限制范围；下界大于上界是运行期错误 |
| `sqrt` | `sqrt(value: float) -> float` | 平方根 |
| `pow` | `pow(base: float, exponent: float) -> float` | 幂 |
| `log` | `log(value: float, base: float) -> float` | 对数；真数必须为正、底数必须为正且不为 1 |
| `sin` | `sin(value: float) -> float` | 正弦 |
| `cos` | `cos(value: float) -> float` | 余弦 |
| `tan` | `tan(value: float) -> float` | 正切 |
| `random` | `random(lower: int, upper: int) -> int` | 含两端的确定性随机数，见下 |

`random` 可复现：VM 用规则名派生种子，同一条规则在同样的输入上跑出同样的结果。

`log` 与写日志的动作函数重名。按第一个实参的运行时类型分派：第一参是字符串时走写日志，否则走数学对数。

## PACC 专属检测

| 函数 | 签名 | 说明 |
|---|---|---|
| `analyze_click_pattern` | `analyze_click_pattern(events: list[ClickEvent]) -> ClickPattern` | 点击模式分析 |
| `analyze_click_pattern` | `analyze_click_pattern(events: list[AttackEvent]) -> ClickPattern` | 同上，接受攻击事件 |
| `detect_aim_assist` | `detect_aim_assist(events: list[MouseMoveEvent]) -> float` | 自瞄检测置信度 |
| `detect_speed_hack` | `detect_speed_hack(events: list[MoveEvent]) -> float` | 加速检测置信度 |
| `detect_fly` | `detect_fly(events: list[MoveEvent]) -> float` | 飞行检测置信度 |
| `detect_reach` | `detect_reach(events: list[AttackEvent]) -> float` | reach 检测置信度 |
| `detect_autoclicker` | `detect_autoclicker(events: list[ClickEvent]) -> float` | 连点器检测置信度 |
| `is_human_click_pattern` | `is_human_click_pattern(pattern: ClickPattern) -> bool` | 是否为人类点击模式 |
| `entropy` | `entropy(values: list[number]) -> float` | 香农信息熵（bit） |
| `cps_variance` | `cps_variance(events: list[ClickEvent]) -> float` | 相邻区间瞬时 CPS 的方差 |
| `reaction_time` | `reaction_time(events: list[Event]) -> float` | 相邻 `time_ms` 差值的中位数 |

`ClickPattern` 的成员：`cps`、`interval_stddev`（`float`）；方法：`is_human_like()`、`to_json()`。

这几个检测函数都是启发式，阈值是命名常量，含义见 `PrlStdlib` 的常量注释：加速上限 10.0 格/秒，reach 上限 3.0 格，连点间隔标准差参考值 10ms、CPS 区间 8~20。

## 动作

动作函数把副作用交回宿主，返回值对规则没有意义。

| 函数 | 签名 | 说明 |
|---|---|---|
| `emit_alert` | `emit_alert(type: string, confidence: float, evidence: map[any, any]) -> DetectionResult` | 发出告警；`ruleName` 由 `RuleManager` 收集时补上 |
| `record_evidence` | `record_evidence(key: string, value: any) -> void` | 记录证据 |
| `trigger_redscreen` | `trigger_redscreen(reason: string) -> void` | 触发红屏警告 |
| `log` | `log(level: string, message: string) -> void` | 写日志 |
| `ban_feature` | `ban_feature(feature: string, duration: int) -> void` | 临时禁用某个客户端功能，不是封禁玩家 |

## 显式转换

| 函数 | 签名 | 说明 |
|---|---|---|
| `to_float` | `to_float(value: number) -> float` | 转 float |
| `to_int` | `to_int(value: number) -> int` | 转 int |
| `to_string` | `to_string(value: any) -> string` | 转 string |

PRL 没有隐式数值转换，`airborne_ms / 1000` 这类写法里 `airborne_ms` 是 `int`，要小数得先 `to_float(airborne_ms) / 1000.0`。

## 示例规则用到的补充函数

| 函数 | 签名 | 说明 |
|---|---|---|
| `accuracy` | `accuracy(events: list[AttackEvent]) -> float` | 命中率：伤害大于 0 的事件占比；空表给 `0.0` |
| `last_n` | `last_n(values: list[T], n: int) -> list[T]` | 取末尾 N 个；`n <= 0` 给空表 |

## 直接调用标准库

脱离规则引擎时可以直接用 `PrlStdlib`：

```java
PrlStdlib stdlib = new PrlStdlib(host, 0L);      // 指定种子，保证 random 可复现
boolean known = PrlStdlib.supports("detect_fly"); // 名字是否由标准库兜底
Object value = stdlib.call("count", new Object[]{list});
```

`withSeed(long)` 复制一个只换随机种子的实例。