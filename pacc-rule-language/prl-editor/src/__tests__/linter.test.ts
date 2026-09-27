import { describe, expect, it } from 'vitest'
import { lintSource } from '../Linter'
import type { Diagnostic } from '../types'

function codesOf(source: string, hostFunctions?: readonly string[]): string[] {
  return lintSource(source, { hostFunctions }).map((d: Diagnostic) => d.code ?? '')
}

function hasCode(source: string, code: string, hostFunctions?: readonly string[]): boolean {
  return codesOf(source, hostFunctions).includes(code)
}

/** 与 examples/kill_aura.prl 等价的最小规则，本地检查必须零诊断。 */
const VALID_RULE = `# kill_aura：击杀光环

rule "kill_aura_detection" {
    version: "1.2.0"
    severity: high

    input {
        player: PlayerContext
        attack_events: list[AttackEvent]
    }

    let attack_rate = rate(attack_events, 1s)
    let hit_accuracy = accuracy(attack_events)

    when:
        attack_rate > 8 per_second
        AND hit_accuracy > 0.95
        AND NOT player.is_trusted()

    then:
        emit_alert(type = "KILL_AURA", confidence = hit_accuracy)
        if hit_accuracy > 0.9:
            trigger_redscreen(reason = "kill_aura_detected")
        end
}
`

describe('lintSource / 合法规则', () => {
  it('完整规则的本地检查为零诊断', () => {
    expect(lintSource(VALID_RULE)).toEqual([])
  })

  it('空源码不报任何问题', () => {
    expect(lintSource('')).toEqual([])
    expect(lintSource('\n\n   \n')).toEqual([])
  })

  it('纯注释内容不参与结构检查', () => {
    expect(lintSource('# }}} ))) end\n# rule "x" {')).toEqual([])
  })

  it('字符串里的 # 不会被当成注释', () => {
    expect(lintSource('let a = "# 这不是注释"')).toEqual([])
  })
})

describe('lintSource / 词法问题', () => {
  it('未闭合的字符串报 PRL-L-STR', () => {
    const source = 'let a = "abc'
    expect(hasCode(source, 'PRL-L-STR')).toBe(true)
    expect(lintSource(source)[0].severity).toBe('error')
  })

  it('已闭合的字符串不报', () => {
    expect(hasCode('let a = "abc"', 'PRL-L-STR')).toBe(false)
  })

  it('字符串诊断带列号（指向左引号）', () => {
    const diagnostic = lintSource('let a = "abc').find((d) => d.code === 'PRL-L-STR')
    expect(diagnostic?.column).toBe(9)
  })
})

describe('lintSource / 括号配对', () => {
  it('括号未闭合报 PRL-L-BRACE', () => {
    expect(hasCode('let a = (1', 'PRL-L-BRACE')).toBe(true)
  })

  it('多余的右括号报 PRL-L-BRACE', () => {
    expect(hasCode('let a = 1)', 'PRL-L-BRACE')).toBe(true)
  })

  it('括号类型不匹配报 PRL-L-BRACE', () => {
    const diagnostics = lintSource('let a = (1]')
    expect(diagnostics.some((d) => d.code === 'PRL-L-BRACE')).toBe(true)
  })

  it('成对括号不报', () => {
    expect(hasCode('let a = f(1, [2, 3])', 'PRL-L-BRACE')).toBe(false)
  })
})

describe('lintSource / rule 结构', () => {
  it('规则名不是字符串字面量报 PRL-L-RULE', () => {
    expect(hasCode('rule bad_name {', 'PRL-L-RULE')).toBe(true)
  })

  it('rule 声明后缺少 { 报 PRL-L-BRACE', () => {
    expect(hasCode('rule "no_brace"', 'PRL-L-BRACE')).toBe(true)
  })

  it('重复的规则名给出警告', () => {
    const duplicated = `rule "dup" {
    when:
        a > 1
    then:
        emit_alert(type = "X")
}

rule "dup" {
    when:
        a > 1
    then:
        emit_alert(type = "X")
}
`
    const hit = lintSource(duplicated).find((d) => d.code === 'PRL-L-RULE')
    expect(hit?.severity).toBe('warning')
    expect(hit?.message).toContain('dup')
  })

  it('缺少 when: 条件段报 PRL-L-SECTION', () => {
    const source = `rule "only_then" {
    then:
        emit_alert(type = "X")
}
`
    expect(lintSource(source).some((d) => d.code === 'PRL-L-SECTION' && d.message.includes('when'))).toBe(true)
  })

  it('缺少 then: 动作段报 PRL-L-SECTION', () => {
    const source = `rule "only_when" {
    when:
        a > 1
}
`
    expect(lintSource(source).some((d) => d.code === 'PRL-L-SECTION' && d.message.includes('then'))).toBe(true)
  })
})

describe('lintSource / 块与冒号', () => {
  it('when 块头缺冒号报 PRL-L-COLON', () => {
    expect(hasCode('when', 'PRL-L-COLON')).toBe(true)
    expect(hasCode('when:', 'PRL-L-COLON')).toBe(false)
  })

  it('if 块缺少配对的 end 报 PRL-L-BLOCK', () => {
    const source = `if x > 1:
    a = 1
`
    expect(hasCode(source, 'PRL-L-BLOCK')).toBe(true)
  })

  it('多余的 end 报 PRL-L-BLOCK', () => {
    expect(hasCode('end', 'PRL-L-BLOCK')).toBe(true)
  })

  it('if / end 配对且缩进一致时不报块错误', () => {
    const source = `if x > 1:
    a = 1
end
`
    const diagnostics = lintSource(source)
    expect(diagnostics.some((d) => d.code === 'PRL-L-BLOCK')).toBe(false)
  })

  it('end 缩进与开块行不一致时报 PRL-L-INDENT', () => {
    const source = `if x > 1:
    a = 1
  end
`
    expect(lintSource(source).some((d) => d.code === 'PRL-L-INDENT' && d.message.includes('end'))).toBe(true)
  })
})

describe('lintSource / 缩进', () => {
  it('用 Tab 缩进给出警告', () => {
    expect(hasCode('\tlet a = 1', 'PRL-L-INDENT')).toBe(true)
  })

  it('块体不比开块行更深时报 PRL-L-INDENT', () => {
    const source = `if x > 1:
a = 1
end
`
    expect(hasCode(source, 'PRL-L-INDENT')).toBe(true)
  })

  it('4 空格缩进不报', () => {
    const source = `if x > 1:
    a = 1
end
`
    expect(hasCode(source, 'PRL-L-INDENT')).toBe(false)
  })
})

describe('lintSource / 函数名解析', () => {
  it('不在标准库里的裸调用报 PRL-L-FUNC（警告）', () => {
    const hit = lintSource('let a = does_not_exist(1)').find((d) => d.code === 'PRL-L-FUNC')
    expect(hit?.severity).toBe('warning')
    expect(hit?.message).toContain('does_not_exist')
  })

  it('标准库函数不报', () => {
    expect(hasCode('let a = average(values)', 'PRL-L-FUNC')).toBe(false)
    expect(hasCode('let a = accuracy(events)', 'PRL-L-FUNC')).toBe(false)
    expect(hasCode('let a = detect_fly(moves)', 'PRL-L-FUNC')).toBe(false)
  })

  it('宿主白名单里的函数不报', () => {
    expect(hasCode('let a = custom_host_fn(1)', 'PRL-L-FUNC')).toBe(true)
    expect(hasCode('let a = custom_host_fn(1)', 'PRL-L-FUNC', ['custom_host_fn'])).toBe(false)
  })

  it('对象方法调用不报未知函数', () => {
    expect(hasCode('let a = player.is_trusted()', 'PRL-L-FUNC')).toBe(false)
  })

  it('调用已声明的局部变量不报未知函数', () => {
    const source = `let helper = 1
let b = helper(2)
`
    expect(hasCode(source, 'PRL-L-FUNC')).toBe(false)
  })
})

describe('lintSource / 诊断排序与来源', () => {
  it('诊断按行号升序排列', () => {
    const source = `let a = "unclosed
let b = 1)
when
`
    const lines = lintSource(source).map((d) => d.line)
    const sorted = [...lines].sort((a, b) => a - b)
    expect(lines).toEqual(sorted)
  })

  it('本地检查的诊断来源标记为 local', () => {
    for (const diagnostic of lintSource('let a = "abc\nwhen')) {
      expect(diagnostic.source).toBe('local')
    }
  })

  it('诊断码统一为 PRL-L 前缀的本地码', () => {
    const source = `rule bad {
    when
        a = (1
}
`
    for (const code of codesOf(source)) {
      expect(code.startsWith('PRL-L-')).toBe(true)
    }
  })
})