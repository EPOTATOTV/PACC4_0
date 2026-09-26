/**
 * PRL（PACC Rule Language）语言描述：tokenizer、关键字表、标准库函数表、补全候选、声明提取。
 *
 * 这里是纯逻辑，不依赖 React，也不依赖 DOM，可以直接单测。
 *
 * 两个上游来源：
 * - 语法：设计文档 §2.2 / §2.6，以及 pacc-rule-language/examples/*.prl 的真实规则；
 * - 标准库签名：Java 侧 com.potatotv.prl.types.PrlSignatures（§2.4），中文说明沿用其中的描述。
 */

// ---------------------------------------------------------------------------
// Token
// ---------------------------------------------------------------------------

export type PrlTokenType =
  | 'plain'
  | 'keyword'
  | 'type'
  | 'function'
  | 'member'
  | 'number'
  | 'unit'
  | 'string'
  | 'comment'
  | 'operator'
  | 'punct'
  | 'metadata'
  | 'severity'
  | 'identifier'

export interface PrlToken {
  type: PrlTokenType
  value: string
  /** 在本行内的起止下标（左闭右开）。 */
  start: number
  end: number
  /** 仅字符串 token 有：引号是否在本行内闭合。未闭合时 linter 会报错。 */
  closed?: boolean
}

export interface PrlTokenLine {
  line: number
  tokens: PrlToken[]
}

/** PRL 缩进固定 4 空格（仓库里所有示例规则都是 4 空格）。 */
export const PRL_INDENT = '    '

// ---------------------------------------------------------------------------
// 关键字 / 类型 / 单位 / 元数据 / 严重级（§2.2.2）
// ---------------------------------------------------------------------------

export interface PrlKeywordInfo {
  word: string
  description: string
}

export const PRL_KEYWORDS: readonly PrlKeywordInfo[] = [
  { word: 'rule', description: '声明一条规则，规则名必须是字符串' },
  { word: 'input', description: '声明强类型输入变量' },
  { word: 'let', description: '声明局部变量' },
  { word: 'when', description: '触发条件段，末尾要写冒号' },
  { word: 'then', description: '动作段，末尾要写冒号' },
  { word: 'if', description: '条件分支，用 end 收尾' },
  { word: 'else', description: '否则分支' },
  { word: 'for', description: '遍历集合，用 end 收尾' },
  { word: 'while', description: '条件循环，引擎有迭代上限，用 end 收尾' },
  { word: 'in', description: '成员判断 / 遍历' },
  { word: 'return', description: '提前结束本规则的执行' },
  { word: 'end', description: '结束 if / for / while 块' },
  { word: 'and', description: '逻辑与（也可写 AND）' },
  { word: 'or', description: '逻辑或（也可写 OR）' },
  { word: 'not', description: '逻辑非（也可写 NOT）' },
  { word: 'true', description: '布尔真' },
  { word: 'false', description: '布尔假' },
  { word: 'null', description: '空值' },
]

const PRL_KEYWORD_SET: ReadonlySet<string> = new Set(PRL_KEYWORDS.map((k) => k.word))

/** 内建类型名（§2.2.2）。 */
export const PRL_TYPES: readonly string[] = [
  'int',
  'float',
  'bool',
  'string',
  'list',
  'map',
  'set',
  'tuple',
  'number',
  'any',
  'void',
]

/** 宿主提供的类型名，来自 PrlSignatures 里注册的宿主类型与示例规则的 input 声明。 */
export const PRL_HOST_TYPES: readonly string[] = [
  'PlayerContext',
  'SystemState',
  'AttackEvent',
  'MoveEvent',
  'ClickEvent',
  'MouseMoveEvent',
  'MiningEvent',
  'ClickPattern',
  'Stats',
  'DetectionResult',
  'Duration',
  'Event',
]

const PRL_TYPE_SET: ReadonlySet<string> = new Set([...PRL_TYPES, ...PRL_HOST_TYPES])

/** 单位（§2.2.2）。数字后紧跟单位名时单独成一个 token，如 `300s` / `10MB`。 */
export const PRL_UNITS: readonly string[] = [
  'per_second',
  'per_minute',
  'ms',
  's',
  'm',
  'h',
  'KB',
  'MB',
  'GB',
]

const PRL_UNIT_SET: ReadonlySet<string> = new Set(PRL_UNITS)

/** 单位名按长度倒序，保证 `ms` 比 `s` 先匹配、`per_second` 比 `s` 先匹配。 */
const PRL_UNITS_BY_LENGTH: readonly string[] = [...PRL_UNITS].sort((a, b) => b.length - a.length)

/** 规则元数据键名（§2.2.2）。 */
export const PRL_METADATA_KEYS: readonly string[] = [
  'version',
  'author',
  'severity',
  'category',
  'cooldown',
  'enabled',
  'description',
]

const PRL_METADATA_SET: ReadonlySet<string> = new Set(PRL_METADATA_KEYS)

/** 严重级取值。 */
export const PRL_SEVERITIES: readonly string[] = ['low', 'medium', 'high', 'critical']

const PRL_SEVERITY_SET: ReadonlySet<string> = new Set(PRL_SEVERITIES)

/** 需要以 `:` 收尾的块头关键字；`rule` 用的是 `{`，不在此列。 */
export const PRL_BLOCK_HEADERS: readonly string[] = ['when', 'then', 'if', 'else', 'for', 'while']

/** 用 `end` 收尾的块头；`when` / `then` 是规则段，由 `}` 收尾。 */
export const PRL_END_BLOCKS: readonly string[] = ['if', 'else', 'for', 'while']

// ---------------------------------------------------------------------------
// 标准库（§2.4，以 PrlSignatures.standard() 为准）
// ---------------------------------------------------------------------------

export type PrlFunctionCategory =
  | '统计'
  | '时序'
  | '集合'
  | '字符串'
  | '数学'
  | 'PACC 检测'
  | '动作'
  | '转换'

export interface PrlFunctionInfo {
  name: string
  category: PrlFunctionCategory
  /** 主签名，补全下拉里直接展示。 */
  signature: string
  description: string
  /** 同名重载的补充签名，如 int/float 两版 abs。 */
  overloads?: string[]
}

export const PRL_FUNCTIONS: readonly PrlFunctionInfo[] = [
  // ---- §2.4.1 统计 ----
  { name: 'average', category: '统计', signature: 'average(values: list[number]) -> float', description: '平均值' },
  { name: 'median', category: '统计', signature: 'median(values: list[number]) -> float', description: '中位数' },
  { name: 'stddev', category: '统计', signature: 'stddev(values: list[number]) -> float', description: '标准差' },
  { name: 'variance', category: '统计', signature: 'variance(values: list[number]) -> float', description: '方差' },
  {
    name: 'min',
    category: '统计',
    signature: 'min(values: list[int]) -> int',
    description: '最小值',
    overloads: ['min(values: list[float]) -> float'],
  },
  {
    name: 'max',
    category: '统计',
    signature: 'max(values: list[int]) -> int',
    description: '最大值',
    overloads: ['max(values: list[float]) -> float'],
  },
  {
    name: 'percentile',
    category: '统计',
    signature: 'percentile(values: list[number], percent: number) -> float',
    description: '百分位数',
  },
  {
    name: 'sum',
    category: '统计',
    signature: 'sum(values: list[int]) -> int',
    description: '求和',
    overloads: ['sum(values: list[float]) -> float'],
  },
  { name: 'count', category: '统计', signature: 'count(values: list[T]) -> int', description: '元素个数' },
  {
    name: 'rate',
    category: '统计',
    signature: 'rate(events: list[Event], window: int) -> float',
    description: '单位时间事件数，window 是毫秒',
  },
  {
    name: 'frequency',
    category: '统计',
    signature: 'frequency(values: list[T]) -> map[T, int]',
    description: '频率分布',
  },
  {
    name: 'correlation',
    category: '统计',
    signature: 'correlation(xs: list[number], ys: list[number]) -> float',
    description: '皮尔逊相关系数',
  },
  {
    name: 'zscore',
    category: '统计',
    signature: 'zscore(value: number, values: list[number]) -> float',
    description: 'Z 分数',
  },
  {
    name: 'outliers',
    category: '统计',
    signature: 'outliers(values: list[number], factor: number) -> list[int]',
    description: '异常值索引（IQR）',
  },

  // ---- §2.4.2 时序 ----
  {
    name: 'sliding_window',
    category: '时序',
    signature: 'sliding_window(events: list[T], window: int) -> list[list[T]]',
    description: '滑动窗口',
  },
  {
    name: 'burst_count',
    category: '时序',
    signature: 'burst_count(events: list[Event], window: int, threshold: int) -> int',
    description: '突发次数',
  },
  {
    name: 'interval_stats',
    category: '时序',
    signature: 'interval_stats(events: list[T]) -> Stats',
    description: '事件间隔统计，取 .mean / .stddev',
  },
  { name: 'trend', category: '时序', signature: 'trend(values: list[number]) -> float', description: '线性趋势斜率' },
  { name: 'change_rate', category: '时序', signature: 'change_rate(values: list[number]) -> float', description: '变化率' },

  // ---- §2.4.3 集合 ----
  {
    name: 'filter',
    category: '集合',
    signature: 'filter(values: list[T], predicate: (T) -> bool) -> list[T]',
    description: '过滤',
  },
  {
    name: 'map',
    category: '集合',
    signature: 'map(values: list[T], transform: (T) -> U) -> list[U]',
    description: '映射',
  },
  {
    name: 'reduce',
    category: '集合',
    signature: 'reduce(values: list[T], initial: U, folder: (U, T) -> U) -> U',
    description: '归约',
  },
  {
    name: 'sort',
    category: '集合',
    signature: 'sort(values: list[T], comparator: (T, T) -> int) -> list[T]',
    description: '排序',
  },
  {
    name: 'group_by',
    category: '集合',
    signature: 'group_by(values: list[T], key: (T) -> K) -> map[K, list[T]]',
    description: '分组',
  },
  { name: 'first', category: '集合', signature: 'first(values: list[T]) -> T', description: '第一个元素' },
  { name: 'last', category: '集合', signature: 'last(values: list[T]) -> T', description: '最后一个元素' },
  { name: 'take', category: '集合', signature: 'take(values: list[T], n: int) -> list[T]', description: '取前 N 个' },
  { name: 'skip', category: '集合', signature: 'skip(values: list[T], n: int) -> list[T]', description: '跳过前 N 个' },
  {
    name: 'contains',
    category: '集合',
    signature: 'contains(values: list[T], value: T) -> bool',
    description: '是否包含',
    overloads: ['contains(text: string, substring: string) -> bool 子串判断'],
  },
  { name: 'unique', category: '集合', signature: 'unique(values: list[T]) -> list[T]', description: '去重' },
  { name: 'flatten', category: '集合', signature: 'flatten(values: list[list[T]]) -> list[T]', description: '展平' },
  {
    name: 'zip',
    category: '集合',
    signature: 'zip(left: list[A], right: list[B]) -> list[tuple]',
    description: '拉链',
  },

  // ---- §2.4.4 字符串 ----
  { name: 'length', category: '字符串', signature: 'length(text: string) -> int', description: '长度' },
  {
    name: 'starts_with',
    category: '字符串',
    signature: 'starts_with(text: string, prefix: string) -> bool',
    description: '前缀匹配',
  },
  {
    name: 'ends_with',
    category: '字符串',
    signature: 'ends_with(text: string, suffix: string) -> bool',
    description: '后缀匹配',
  },
  {
    name: 'regex_match',
    category: '字符串',
    signature: 'regex_match(text: string, pattern: string) -> bool',
    description: '正则匹配',
  },
  {
    name: 'regex_extract',
    category: '字符串',
    signature: 'regex_extract(text: string, pattern: string) -> list[string]',
    description: '正则提取',
  },
  { name: 'to_lower', category: '字符串', signature: 'to_lower(text: string) -> string', description: '转小写' },
  { name: 'to_upper', category: '字符串', signature: 'to_upper(text: string) -> string', description: '转大写' },
  { name: 'trim', category: '字符串', signature: 'trim(text: string) -> string', description: '去首尾空白' },
  {
    name: 'split',
    category: '字符串',
    signature: 'split(text: string, separator: string) -> list[string]',
    description: '分割',
  },
  {
    name: 'join',
    category: '字符串',
    signature: 'join(parts: list[string], separator: string) -> string',
    description: '连接',
  },
  {
    name: 'levenshtein',
    category: '字符串',
    signature: 'levenshtein(left: string, right: string) -> int',
    description: '编辑距离',
  },
  {
    name: 'similarity',
    category: '字符串',
    signature: 'similarity(left: string, right: string) -> float',
    description: '文本相似度',
  },

  // ---- §2.4.5 数学 ----
  {
    name: 'abs',
    category: '数学',
    signature: 'abs(value: int) -> int',
    description: '绝对值',
    overloads: ['abs(value: float) -> float'],
  },
  { name: 'round', category: '数学', signature: 'round(value: float, digits: int) -> float', description: '四舍五入' },
  { name: 'floor', category: '数学', signature: 'floor(value: float) -> int', description: '向下取整' },
  { name: 'ceil', category: '数学', signature: 'ceil(value: float) -> int', description: '向上取整' },
  {
    name: 'clamp',
    category: '数学',
    signature: 'clamp(value: float, lower: float, upper: float) -> float',
    description: '限制范围',
    overloads: ['clamp(value: int, lower: int, upper: int) -> int'],
  },
  { name: 'sqrt', category: '数学', signature: 'sqrt(value: float) -> float', description: '平方根' },
  { name: 'pow', category: '数学', signature: 'pow(base: float, exponent: float) -> float', description: '幂' },
  {
    name: 'log',
    category: '动作',
    signature: 'log(level: string, message: string) -> void',
    description: '写日志（数学模块另有 log(value: float, base: float) 对数重载）',
    overloads: ['log(value: float, base: float) -> float 对数'],
  },
  { name: 'sin', category: '数学', signature: 'sin(value: float) -> float', description: '正弦' },
  { name: 'cos', category: '数学', signature: 'cos(value: float) -> float', description: '余弦' },
  { name: 'tan', category: '数学', signature: 'tan(value: float) -> float', description: '正切' },
  {
    name: 'random',
    category: '数学',
    signature: 'random(lower: int, upper: int) -> int',
    description: '确定性随机数（同输入可复现）',
  },

  // ---- §2.4.6 PACC 专属检测 ----
  {
    name: 'analyze_click_pattern',
    category: 'PACC 检测',
    signature: 'analyze_click_pattern(events: list[ClickEvent]) -> ClickPattern',
    description: '点击模式分析',
    overloads: ['analyze_click_pattern(events: list[AttackEvent]) -> ClickPattern'],
  },
  {
    name: 'detect_aim_assist',
    category: 'PACC 检测',
    signature: 'detect_aim_assist(events: list[MouseMoveEvent]) -> float',
    description: '自瞄检测置信度',
  },
  {
    name: 'detect_speed_hack',
    category: 'PACC 检测',
    signature: 'detect_speed_hack(events: list[MoveEvent]) -> float',
    description: '加速检测置信度',
  },
  { name: 'detect_fly', category: 'PACC 检测', signature: 'detect_fly(events: list[MoveEvent]) -> float', description: '飞行检测置信度' },
  { name: 'detect_reach', category: 'PACC 检测', signature: 'detect_reach(events: list[AttackEvent]) -> float', description: 'reach 检测置信度' },
  {
    name: 'detect_autoclicker',
    category: 'PACC 检测',
    signature: 'detect_autoclicker(events: list[ClickEvent]) -> float',
    description: '连点器检测置信度',
  },
  {
    name: 'is_human_click_pattern',
    category: 'PACC 检测',
    signature: 'is_human_click_pattern(pattern: ClickPattern) -> bool',
    description: '是否为人类点击模式',
  },
  { name: 'entropy', category: 'PACC 检测', signature: 'entropy(values: list[number]) -> float', description: '信息熵' },
  { name: 'cps_variance', category: 'PACC 检测', signature: 'cps_variance(events: list[ClickEvent]) -> float', description: 'CPS 方差' },
  {
    name: 'reaction_time',
    category: 'PACC 检测',
    signature: 'reaction_time(events: list[Event]) -> float',
    description: '平均反应时间',
  },
  {
    name: 'accuracy',
    category: 'PACC 检测',
    signature: 'accuracy(events: list[AttackEvent]) -> float',
    description: '命中率',
  },

  // ---- §2.4.7 动作（副作用） ----
  {
    name: 'emit_alert',
    category: '动作',
    signature: 'emit_alert(type: string, confidence: float, evidence: map) -> DetectionResult',
    description: '发出告警（登记项）',
  },
  {
    name: 'record_evidence',
    category: '动作',
    signature: 'record_evidence(key: string, value: any) -> void',
    description: '记录证据',
  },
  {
    name: 'trigger_redscreen',
    category: '动作',
    signature: 'trigger_redscreen(reason: string) -> void',
    description: '触发红屏强制警告',
  },
  {
    name: 'ban_feature',
    category: '动作',
    signature: 'ban_feature(feature: string, duration: int) -> void',
    description: '临时禁用某个功能（不是封禁玩家）',
  },
  {
    name: 'last_n',
    category: '集合',
    signature: 'last_n(values: list[T], n: int) -> list[T]',
    description: '取末尾 N 个',
  },

  // ---- §2.3.5 显式转换 ----
  { name: 'to_float', category: '转换', signature: 'to_float(value: number) -> float', description: '转 float' },
  { name: 'to_int', category: '转换', signature: 'to_int(value: number) -> int', description: '转 int' },
  { name: 'to_string', category: '转换', signature: 'to_string(value: any) -> string', description: '转 string' },
]

/** 标准库函数名集合，linter 用它判断「未知函数」。 */
export const PRL_STDLIB_NAMES: ReadonlySet<string> = new Set(PRL_FUNCTIONS.map((f) => f.name))

const PRL_FUNCTION_BY_NAME: ReadonlyMap<string, PrlFunctionInfo> = new Map(
  PRL_FUNCTIONS.map((f) => [f.name, f]),
)

export function findPrlFunction(name: string): PrlFunctionInfo | undefined {
  return PRL_FUNCTION_BY_NAME.get(name)
}

// ---------------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------------

const MULTI_CHAR_OPERATORS: readonly string[] = [
  '->',
  '|>',
  '..',
  '==',
  '!=',
  '>=',
  '<=',
  '+=',
  '-=',
  '*=',
  '/=',
  '%=',
  '++',
  '--',
]

const SINGLE_CHAR_OPERATORS = '+-*/%^<>=!|&'

const PUNCTUATION = '()[]{},.:;'

function isIdentifierStart(ch: string): boolean {
  return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch === '_'
}

function isIdentifierPart(ch: string): boolean {
  return isIdentifierStart(ch) || (ch >= '0' && ch <= '9')
}

/**
 * 逐行 token 化。PRL 的字符串与注释都不跨行，所以按行处理是安全的（也是编辑器高亮的做法）。
 */
export function tokenizeLine(line: string): PrlToken[] {
  const tokens: PrlToken[] = []
  const n = line.length
  let i = 0
  let prevSignificant: PrlToken | null = null

  const emit = (type: PrlTokenType, start: number, end: number): PrlToken => {
    const token: PrlToken = { type, value: line.slice(start, end), start, end }
    tokens.push(token)
    if (type !== 'plain') {
      prevSignificant = token
    }
    return token
  }

  // prevSignificant 只在 emit 闭包里被改写，外层直接读会被控制流分析窄化成初始值。
  // 包一层函数读，既拿到真实的上一个有效 token，也不破坏类型收窄。
  const memberPosition = (): boolean =>
    prevSignificant !== null && prevSignificant.type === 'punct' && prevSignificant.value === '.'

  while (i < n) {
    const ch = line[i]

    // 空白
    if (ch === ' ' || ch === '\t') {
      const start = i
      while (i < n && (line[i] === ' ' || line[i] === '\t')) {
        i += 1
      }
      emit('plain', start, i)
      continue
    }

    // 注释：`#` 到行尾
    if (ch === '#') {
      emit('comment', i, n)
      i = n
      continue
    }

    // 字符串：双引号 / 单引号，带 f 前缀的插值字符串
    const isFString = (ch === 'f' || ch === 'F') && i + 1 < n && (line[i + 1] === '"' || line[i + 1] === "'")
    if (ch === '"' || ch === "'" || isFString) {
      const start = i
      const quoteIndex = isFString ? i + 1 : i
      const quote = line[quoteIndex]
      let j = quoteIndex + 1
      let closed = false
      while (j < n) {
        if (line[j] === '\\') {
          j += 2
          continue
        }
        if (line[j] === quote) {
          closed = true
          j += 1
          break
        }
        j += 1
      }
      const token = emit('string', start, Math.min(j, n))
      token.closed = closed
      i = Math.min(j, n)
      continue
    }

    // 数字：十进制 / 十六进制 / 二进制，允许 `_` 分隔符
    if (ch >= '0' && ch <= '9') {
      const start = i
      if (line.startsWith('0x', i) || line.startsWith('0X', i)) {
        i += 2
        while (i < n && (/[0-9a-fA-F_]/.test(line[i]))) {
          i += 1
        }
      } else if (line.startsWith('0b', i) || line.startsWith('0B', i)) {
        i += 2
        while (i < n && (line[i] === '0' || line[i] === '1' || line[i] === '_')) {
          i += 1
        }
      } else {
        // 十进制：数字与 `_` 分隔符，允许一个小数点；遇到 `..`（范围运算）就停
        while (i < n && ((line[i] >= '0' && line[i] <= '9') || line[i] === '_')) {
          i += 1
        }
        if (i < n && line[i] === '.' && line[i + 1] !== '.') {
          i += 1
          while (i < n && ((line[i] >= '0' && line[i] <= '9') || line[i] === '_')) {
            i += 1
          }
        }
      }
      emit('number', start, i)

      // 紧贴数字的量纲后缀：`300s` / `10MB`
      const unit = PRL_UNITS_BY_LENGTH.find(
        (u) => line.startsWith(u, i) && !isIdentifierPart(line[i + u.length] ?? ''),
      )
      if (unit) {
        emit('unit', i, i + unit.length)
        i += unit.length
      }
      continue
    }

    // 标识符 / 关键字
    if (isIdentifierStart(ch)) {
      const start = i
      i += 1
      while (i < n && isIdentifierPart(line[i])) {
        i += 1
      }
      const word = line.slice(start, i)
      const lower = word.toLowerCase()

      let j = i
      while (j < n && (line[j] === ' ' || line[j] === '\t')) {
        j += 1
      }
      const isCall = line[j] === '('

      let type: PrlTokenType = 'identifier'
      if (memberPosition()) {
        type = 'member'
      } else if (isCall) {
        type = 'function'
      } else if (PRL_KEYWORD_SET.has(lower)) {
        type = 'keyword'
      } else if (PRL_SEVERITY_SET.has(lower)) {
        type = 'severity'
      } else if (PRL_METADATA_SET.has(lower)) {
        type = 'metadata'
      } else if (PRL_UNIT_SET.has(word)) {
        type = 'unit'
      } else if (PRL_TYPE_SET.has(word) || PRL_TYPE_SET.has(lower)) {
        type = 'type'
      }
      emit(type, start, i)
      continue
    }

    // 运算符
    const multi = MULTI_CHAR_OPERATORS.find((op) => line.startsWith(op, i))
    if (multi) {
      emit('operator', i, i + multi.length)
      i += multi.length
      continue
    }
    if (SINGLE_CHAR_OPERATORS.includes(ch)) {
      emit('operator', i, i + 1)
      i += 1
      continue
    }
    if (PUNCTUATION.includes(ch)) {
      emit('punct', i, i + 1)
      i += 1
      continue
    }

    // 兜底：未知字符原样输出，避免吞字符导致渲染与源文不一致
    emit('plain', i, i + 1)
    i += 1
  }

  return tokens
}

export function tokenize(source: string): PrlTokenLine[] {
  return source.split(/\r?\n/).map((text, index) => ({ line: index + 1, tokens: tokenizeLine(text) }))
}

/** 去掉行尾注释，返回可直接做结构判断的代码片段（保留原始缩进）。 */
export function stripComment(line: string, tokens?: readonly PrlToken[]): string {
  const list = tokens ?? tokenizeLine(line)
  const comment = list.find((t) => t.type === 'comment')
  return (comment ? line.slice(0, comment.start) : line).replace(/\s+$/, '')
}

/** 代码部分（去注释 + trim），结构判断用。 */
export function codeOf(line: string, tokens?: readonly PrlToken[]): string {
  return stripComment(line, tokens).trim()
}

// ---------------------------------------------------------------------------
// 声明提取（补全与调试变量表要用）
// ---------------------------------------------------------------------------

export interface PrlDeclaration {
  name: string
  kind: 'let' | 'input'
  /** 已知类型时给出，如 `list[MoveEvent]`。 */
  type?: string
  line: number
}

const LET_PATTERN = /^let\s+([A-Za-z_][A-Za-z0-9_]*)/
const INPUT_FIELD_PATTERN = /^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+)$/

/**
 * 提取 `let` 局部变量与 `input {}` 字段声明。
 * 只做正则级的浅解析，够补全与调试面板用。
 */
export function extractDeclarations(source: string): PrlDeclaration[] {
  const out: PrlDeclaration[] = []
  const lines = source.split(/\r?\n/)
  let inInputBlock = false

  for (let index = 0; index < lines.length; index += 1) {
    const code = codeOf(lines[index])
    if (code.length === 0) {
      continue
    }

    if (inInputBlock) {
      const match = INPUT_FIELD_PATTERN.exec(code)
      if (match) {
        out.push({ name: match[1], kind: 'input', type: match[2].trim(), line: index + 1 })
      }
      if (code.includes('}')) {
        inInputBlock = false
      }
      continue
    }

    const inputMatch = /^input\s*\{(.*)$/.exec(code)
    if (inputMatch) {
      const rest = inputMatch[1]
      const closing = rest.indexOf('}')
      const body = closing >= 0 ? rest.slice(0, closing) : rest
      for (const chunk of body.split(/[,;]/)) {
        const field = INPUT_FIELD_PATTERN.exec(chunk.trim())
        if (field) {
          out.push({ name: field[1], kind: 'input', type: field[2].trim(), line: index + 1 })
        }
      }
      inInputBlock = closing < 0
      continue
    }

    const letMatch = LET_PATTERN.exec(code)
    if (letMatch) {
      out.push({ name: letMatch[1], kind: 'let', line: index + 1 })
    }
  }

  return out
}

// ---------------------------------------------------------------------------
// 自动补全
// ---------------------------------------------------------------------------

export type PrlCompletionKind = 'function' | 'keyword' | 'type' | 'variable' | 'input' | 'unit'

export interface PrlCompletionCandidate {
  label: string
  kind: PrlCompletionKind
  /** 下拉右侧的细节：函数显示签名，变量显示类型/来源。 */
  detail: string
  /** 中文说明。 */
  description: string
}

export interface PrlCompletionOptions {
  prefix: string
  declarations?: readonly PrlDeclaration[]
  /** 宿主额外注册的函数名（§2.11.2 callFunction 接管的那批）。 */
  hostFunctions?: readonly string[]
  limit?: number
}

const KEYWORD_CANDIDATES: readonly PrlCompletionCandidate[] = PRL_KEYWORDS.map((k) => ({
  label: k.word,
  kind: 'keyword',
  detail: '关键字',
  description: k.description,
}))

const TYPE_CANDIDATES: readonly PrlCompletionCandidate[] = [...PRL_TYPES, ...PRL_HOST_TYPES].map((name) => ({
  label: name,
  kind: 'type',
  detail: PRL_HOST_TYPES.includes(name) ? '宿主类型' : '内建类型',
  description: PRL_HOST_TYPES.includes(name) ? '宿主声明的类型' : '内建类型名',
}))

const UNIT_CANDIDATES: readonly PrlCompletionCandidate[] = PRL_UNITS.map((name) => ({
  label: name,
  kind: 'unit',
  detail: '单位',
  description: '量纲后缀，跟在数字后面',
}))

const FUNCTION_CANDIDATES: readonly PrlCompletionCandidate[] = PRL_FUNCTIONS.map((f) => ({
  label: f.name,
  kind: 'function',
  detail: f.signature,
  description: f.category === '动作' ? f.description : `${f.description}（${f.category}）`,
}))

const KIND_WEIGHT: Readonly<Record<PrlCompletionKind, number>> = {
  variable: 0,
  input: 0,
  function: 1,
  keyword: 2,
  type: 3,
  unit: 4,
}

/**
 * 按前缀筛选候选。排序：前缀完全命中 > 变量/输入字段 > 标准库函数 > 关键字 > 类型 > 单位。
 */
export function getPrlCompletions(options: PrlCompletionOptions): PrlCompletionCandidate[] {
  const prefix = options.prefix.trim()
  const lower = prefix.toLowerCase()
  const limit = options.limit ?? 50

  const candidates: PrlCompletionCandidate[] = [
    ...FUNCTION_CANDIDATES,
    ...KEYWORD_CANDIDATES,
    ...TYPE_CANDIDATES,
    ...UNIT_CANDIDATES,
  ]

  for (const declaration of options.declarations ?? []) {
    candidates.push({
      label: declaration.name,
      kind: declaration.kind === 'input' ? 'input' : 'variable',
      detail: declaration.kind === 'input' ? (declaration.type ?? 'input 字段') : 'let 变量',
      description: declaration.kind === 'input' ? '本文件 input 声明的字段' : '本文件 let 声明的局部变量',
    })
  }

  const known = new Set(candidates.map((c) => c.label))
  for (const name of options.hostFunctions ?? []) {
    if (known.has(name)) {
      continue
    }
    known.add(name)
    candidates.push({
      label: name,
      kind: 'function',
      detail: `${name}(...) -> any`,
      description: '宿主注册的函数',
    })
  }

  const matched = candidates.filter((c) => (lower.length === 0 ? true : c.label.toLowerCase().startsWith(lower)))

  const scored = matched.map((candidate) => {
    const label = candidate.label.toLowerCase()
    const prefixRank = label === lower ? 0 : 1
    return { candidate, score: prefixRank * 10 + KIND_WEIGHT[candidate.kind] }
  })

  scored.sort((a, b) => {
    if (a.score !== b.score) {
      return a.score - b.score
    }
    return a.candidate.label.localeCompare(b.candidate.label)
  })

  return scored.slice(0, limit).map((s) => s.candidate)
}

/** 取光标前的标识符前缀；返回 `start` 以便补全确认时精确替换。 */
export function identifierPrefixAt(text: string, caret: number): { prefix: string; start: number } {
  let start = caret
  while (start > 0 && isIdentifierPart(text[start - 1])) {
    start -= 1
  }
  if (start > 0 && !isIdentifierStart(text[start])) {
    // 前缀不是从标识符首字符开始的（比如前一位是数字），当作无前缀
    return { prefix: text.slice(start, caret), start }
  }
  return { prefix: text.slice(start, caret), start }
}