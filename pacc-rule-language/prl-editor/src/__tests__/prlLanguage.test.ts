import { describe, expect, it } from 'vitest'
import {
  PRL_FUNCTIONS,
  PRL_STDLIB_NAMES,
  codeOf,
  extractDeclarations,
  findPrlFunction,
  getPrlCompletions,
  identifierPrefixAt,
  stripComment,
  tokenize,
  tokenizeLine,
} from '../prlLanguage'
import type { PrlToken, PrlTokenType } from '../prlLanguage'

function typesOf(line: string): PrlTokenType[] {
  return tokenizeLine(line).map((token: PrlToken) => token.type)
}

function valueOf(line: string, type: PrlTokenType): string[] {
  return tokenizeLine(line)
    .filter((token) => token.type === type)
    .map((token) => token.value)
}

describe('tokenizeLine / 关键字与标识符', () => {
  it('把结构关键字标成 keyword', () => {
    expect(valueOf('rule "kill_aura_detection" {', 'keyword')).toEqual(['rule'])
    expect(typesOf('when:')).toContain('keyword')
    expect(typesOf('then:')).toContain('keyword')
  })

  it('大小写不同的逻辑运算符都算关键字（文档小写、示例大写）', () => {
    expect(valueOf('AND NOT player.is_trusted()', 'keyword')).toEqual(['AND', 'NOT'])
    expect(valueOf('and not true', 'keyword')).toEqual(['and', 'not', 'true'])
  })

  it('缩进与空行不影响 token 覆盖范围', () => {
    const line = '        AND hit_accuracy > 0.95'
    const tokens = tokenizeLine(line)
    expect(tokens.map((t) => t.value).join('')).toBe(line)
    expect(tokens[0].type).toBe('plain')
    expect(tokens[0].value).toBe('        ')
  })

  it('行首 `#` 之后全是注释', () => {
    const tokens = tokenizeLine('# 元数据：severity 取 low/medium/high/critical')
    expect(tokens).toHaveLength(1)
    expect(tokens[0].type).toBe('comment')
  })

  it('行尾注释与代码分开', () => {
    const tokens = tokenizeLine('    severity: high              # low / medium / high / critical')
    expect(tokens[tokens.length - 1].type).toBe('comment')
    expect(valueOf('    severity: high              # low / medium / high / critical', 'severity')).toEqual(['high'])
  })

  it('字符串里的 `#` 不算注释', () => {
    const tokens = tokenizeLine('let tag = "#combat"')
    expect(tokens.filter((t) => t.type === 'comment')).toHaveLength(0)
    expect(valueOf('let tag = "#combat"', 'string')).toEqual(['"#combat"'])
  })
})

describe('tokenizeLine / 字面量', () => {
  it('数字支持小数、下划线分隔、十六进制、二进制', () => {
    expect(valueOf('let a = 42', 'number')).toEqual(['42'])
    expect(valueOf('let b = 3.14', 'number')).toEqual(['3.14'])
    expect(valueOf('let c = 1_000_000', 'number')).toEqual(['1_000_000'])
    expect(valueOf('let d = 0xFF', 'number')).toEqual(['0xFF'])
    expect(valueOf('let e = 0b1010', 'number')).toEqual(['0b1010'])
  })

  it('紧贴数字的量纲后缀拆成单独的 unit token', () => {
    const tokens = tokenizeLine('    cooldown: 300s')
    expect(tokens.map((t) => t.type)).toContain('unit')
    expect(valueOf('    cooldown: 300s', 'unit')).toEqual(['s'])
    expect(valueOf('let r = 10MB', 'unit')).toEqual(['MB'])
    expect(valueOf('let i = 1000ms', 'unit')).toEqual(['ms'])
  })

  it('数字与单位之间有空格时，单位单独成词', () => {
    expect(valueOf('attack_rate > 8 per_second', 'unit')).toEqual(['per_second'])
    expect(valueOf('attack_rate > 8 per_second', 'number')).toEqual(['8'])
  })

  it('范围运算符 `..` 不会被当成小数点', () => {
    const tokens = tokenizeLine('for i in 1..10')
    expect(tokens.filter((t) => t.value === '..')).toHaveLength(1)
    expect(valueOf('for i in 1..10', 'number')).toEqual(['1', '10'])
  })

  it('字符串闭合时 closed 为 true，未闭合为 false', () => {
    const closed = tokenizeLine('let g = \'world\'').find((t) => t.type === 'string')
    expect(closed?.closed).toBe(true)

    const open = tokenizeLine('let f = "hello').find((t) => t.type === 'string')
    expect(open?.closed).toBe(false)
    expect(open?.value).toBe('"hello')
  })

  it('f-string 整体是一个字符串 token', () => {
    const tokens = tokenizeLine('log(level = "warn", message = f"命中: {player.name}")')
    const strings = tokens.filter((t) => t.type === 'string')
    expect(strings.map((t) => t.value)).toEqual(['"warn"', 'f"命中: {player.name}"'])
    expect(strings[1].closed).toBe(true)
  })

  it('转义引号不会提前结束字符串', () => {
    const tokens = tokenizeLine('let h = "line1\\"line2"')
    expect(tokens.filter((t) => t.type === 'string')).toHaveLength(1)
    expect(tokens.find((t) => t.type === 'string')?.closed).toBe(true)
  })
})

describe('tokenizeLine / 函数、成员、类型与元数据', () => {
  it('裸调用标成 function，点号后的成员标成 member', () => {
    const tokens = tokenizeLine('let hit = accuracy(attack_events)')
    expect(tokens.find((t) => t.value === 'accuracy')?.type).toBe('function')

    const memberTokens = tokenizeLine('AND NOT click_pattern.is_human_like()')
    expect(memberTokens.find((t) => t.value === 'is_human_like')?.type).toBe('member')
    expect(memberTokens.find((t) => t.value === 'click_pattern')?.type).toBe('identifier')
  })

  it('内建类型与宿主类型都标成 type', () => {
    expect(typesOf('attack_events: list[AttackEvent]')).toContain('type')
    expect(valueOf('player: PlayerContext', 'type')).toEqual(['PlayerContext'])
    expect(valueOf('let n: int = 1', 'type')).toEqual(['int'])
  })

  it('元数据键与严重级各有独立的 token 类型', () => {
    expect(valueOf('    enabled: true', 'metadata')).toEqual(['enabled'])
    expect(valueOf('    severity: high', 'severity')).toEqual(['high'])
    expect(valueOf('    category: "combat"', 'metadata')).toEqual(['category'])
  })

  it('运算符包含 ->、|>、比较与赋值', () => {
    expect(valueOf('filter(events, e -> e.target_changed)', 'operator')).toEqual(['->'])
    expect(valueOf('    |> average()', 'operator')).toEqual(['|>'])
    expect(valueOf('speed_p95 > 12.0', 'operator')).toEqual(['>'])
    expect(valueOf('let j = true', 'operator')).toEqual(['='])
  })
})

describe('tokenize / 整文件', () => {
  const source = ['# 注释', 'rule "x" {', '    let a = 1', '}'].join('\n')

  it('按行返回，行号从 1 开始', () => {
    const lines = tokenize(source)
    expect(lines).toHaveLength(4)
    expect(lines[0].line).toBe(1)
    expect(lines[3].line).toBe(4)
    expect(lines[2].tokens.map((t) => t.value).join('')).toBe('    let a = 1')
  })

  it('每行 token 拼回去等于原行（不吞字符）', () => {
    const odd = 'let s = f"a {b} # c" + 1_000 + 0x1f   # 尾部注释'
    for (const line of tokenize(odd)) {
      expect(line.tokens.map((t) => t.value).join('')).toBe(odd)
      break
    }
  })
})

describe('stripComment / codeOf', () => {
  it('去掉行尾注释但保留缩进', () => {
    expect(stripComment('    cooldown: 300s   # 冷却')).toBe('    cooldown: 300s')
    expect(codeOf('    cooldown: 300s   # 冷却')).toBe('cooldown: 300s')
  })

  it('没有注释时只裁掉行尾空白', () => {
    expect(stripComment('    let a = 1   ')).toBe('    let a = 1')
    expect(codeOf('   ')).toBe('')
    expect(codeOf('# 只有注释')).toBe('')
  })
})

describe('extractDeclarations', () => {
  const source = [
    'rule "demo" {',
    '    input {',
    '        player: PlayerContext',
    '        attack_events: list[AttackEvent]',
    '    }',
    '    let attack_rate = rate(attack_events, 1s)',
    '    let confidence = 0.92',
    '}',
  ].join('\n')

  it('提取 input 字段及其类型', () => {
    const declarations = extractDeclarations(source)
    const inputs = declarations.filter((d) => d.kind === 'input')
    expect(inputs.map((d) => d.name)).toEqual(['player', 'attack_events'])
    expect(inputs[0].type).toBe('PlayerContext')
    expect(inputs[1].type).toBe('list[AttackEvent]')
    expect(inputs[0].line).toBe(3)
  })

  it('提取 let 局部变量', () => {
    const lets = extractDeclarations(source).filter((d) => d.kind === 'let')
    expect(lets.map((d) => d.name)).toEqual(['attack_rate', 'confidence'])
    expect(lets[0].line).toBe(6)
  })

  it('单行 input 声明也能识别，且不会把字段带进后续行', () => {
    const inline = 'rule "x" {\n    input { a: int }\n    let b = 1\n}'
    const declarations = extractDeclarations(inline)
    expect(declarations.map((d) => `${d.kind}:${d.name}`)).toEqual(['input:a', 'let:b'])
  })
})

describe('getPrlCompletions', () => {
  it('标准库函数带签名与中文说明', () => {
    const items = getPrlCompletions({ prefix: 'perc' })
    const percentile = items.find((item) => item.label === 'percentile')
    expect(percentile?.kind).toBe('function')
    expect(percentile?.detail).toBe('percentile(values: list[number], percent: number) -> float')
    expect(percentile?.description).toContain('百分位')
  })

  it('前缀匹配不区分大小写，完全命中排最前', () => {
    const items = getPrlCompletions({ prefix: 'rate' })
    expect(items[0].label).toBe('rate')
    expect(items[0].kind).toBe('function')
  })

  it('关键字与类型也在候选里', () => {
    const items = getPrlCompletions({ prefix: 'wh' })
    expect(items.find((item) => item.label === 'when')?.kind).toBe('keyword')

    const types = getPrlCompletions({ prefix: 'PlayerC' })
    expect(types.find((item) => item.label === 'PlayerContext')?.kind).toBe('type')
  })

  it('本文件声明的 let 变量与 input 字段会被补全', () => {
    const declarations = extractDeclarations(
      'rule "x" {\n    input {\n        move_events: list[MoveEvent]\n    }\n    let speeds = map(move_events, e -> e.speed)\n}',
    )
    const items = getPrlCompletions({ prefix: 'm', declarations })
    const moveEvents = items.find((item) => item.label === 'move_events')
    expect(moveEvents?.kind).toBe('input')
    expect(moveEvents?.detail).toBe('list[MoveEvent]')
    expect(items.find((item) => item.label === 'map')?.kind).toBe('function')
  })

  it('宿主注册的函数补进候选，且不与标准库重名', () => {
    const items = getPrlCompletions({ prefix: 'pacc_', hostFunctions: ['pacc_uptime'] })
    expect(items.map((item) => item.label)).toEqual(['pacc_uptime'])
    expect(items[0].description).toBe('宿主注册的函数')

    const overlap = getPrlCompletions({ prefix: 'rate', hostFunctions: ['rate'] })
    expect(overlap.filter((item) => item.label === 'rate')).toHaveLength(1)
  })

  it('没有匹配时返回空数组，limit 生效', () => {
    expect(getPrlCompletions({ prefix: 'zzzz' })).toHaveLength(0)
    expect(getPrlCompletions({ prefix: 'a', limit: 3 })).toHaveLength(3)
  })
})

describe('identifierPrefixAt', () => {
  it('取光标前的标识符与起点', () => {
    expect(identifierPrefixAt('let v = av', 10)).toEqual({ prefix: 'av', start: 8 })
    expect(identifierPrefixAt('let v = ', 8)).toEqual({ prefix: '', start: 8 })
    expect(identifierPrefixAt('a.b', 3)).toEqual({ prefix: 'b', start: 2 })
  })
})

describe('标准库表', () => {
  it('PrlSignatures 里的函数名都在表里', () => {
    for (const name of ['rate', 'filter', 'map', 'percentile', 'emit_alert', 'trigger_redscreen', 'last_n']) {
      expect(PRL_STDLIB_NAMES.has(name)).toBe(true)
    }
  })

  it('函数表没有重名条目（同名重载合并成 overloads）', () => {
    const names = PRL_FUNCTIONS.map((f) => f.name)
    expect(new Set(names).size).toBe(names.length)
  })

  it('findPrlFunction 能查到签名与分类', () => {
    const info = findPrlFunction('emit_alert')
    expect(info?.category).toBe('动作')
    expect(info?.signature).toContain('emit_alert(type: string')
    expect(findPrlFunction('nope')).toBeUndefined()
  })
})