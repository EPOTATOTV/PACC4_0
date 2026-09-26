/**
 * 实时语法检查面板。
 *
 * 两条来源并存，UI 上必须能区分：
 * - 本地即时检查：纯前端词法/结构扫描，快，但只认形状不认类型，可能有误报；
 * - 服务端分析：Java 引擎 PrlCompiler.compileChecked 的结论（§2.14），准，但慢，按节流调用。
 *
 * 类型检查、空指针、无限循环、安全、规则冲突只在服务端侧，本地不假装能做。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  analyzeSource,
  describeDiagnosticCode,
  describePrlError,
  diagnosticsFromServer,
  isPrlEndpointConfigured,
  type PrlClientOptions,
} from './api'
import {
  PRL_BLOCK_HEADERS,
  PRL_STDLIB_NAMES,
  codeOf,
  extractDeclarations,
  tokenize,
} from './prlLanguage'
import type { PrlToken } from './prlLanguage'
import type { Diagnostic, DiagnosticSeverity, PrlEndpointProps } from './types'
import { Badge, EmptyState, InlineError, Panel } from './ui'

// ---------------------------------------------------------------------------
// 本地检查（纯函数，可单测）
// ---------------------------------------------------------------------------

export interface PrlLintOptions {
  /** 宿主额外注册的函数名白名单（§2.11.2）。 */
  hostFunctions?: readonly string[]
}

interface BraceEntry {
  ch: string
  line: number
  rule: RuleState | null
}

interface RuleState {
  name: string
  line: number
  hasWhen: boolean
  hasThen: boolean
}

interface BlockEntry {
  kind: string
  line: number
  indent: number
}

const CLOSER: Readonly<Record<string, string>> = { '(': ')', '[': ']', '{': '}' }

function headKeyword(code: string): string {
  const match = /^([A-Za-z_][A-Za-z0-9_]*)/.exec(code)
  return match ? match[1].toLowerCase() : ''
}

/**
 * 对源码做一次本地词法/结构检查。
 * 覆盖：字符串闭合、括号配对、`rule` 结构、块头冒号、`end` 配对、缩进、未知函数名。
 */
export function lintSource(source: string, options: PrlLintOptions = {}): Diagnostic[] {
  const diagnostics: Diagnostic[] = []
  const tokenLines = tokenize(source)
  const rawLines = source.split(/\r?\n/)
  const hostFunctions = new Set(options.hostFunctions ?? [])
  const declaredNames = new Set(extractDeclarations(source).map((d) => d.name))

  const braceStack: BraceEntry[] = []
  const blockStack: BlockEntry[] = []
  const rules: RuleState[] = []
  const ruleLines = new Map<string, number>()
  let currentRule: RuleState | null = null
  let prevOpensBlock = false
  let prevIndent = -1

  const report = (
    line: number,
    severity: DiagnosticSeverity,
    code: string,
    message: string,
    column?: number,
  ): void => {
    diagnostics.push({ line, column, severity, source: 'local', code, message })
  }

  for (let index = 0; index < rawLines.length; index += 1) {
    const lineNo = index + 1
    const raw = rawLines[index]
    const tokens: PrlToken[] = tokenLines[index]?.tokens ?? []
    const code = codeOf(raw, tokens)

    // 字符串闭合：字符串 token 带 closed 标记
    for (const token of tokens) {
      if (token.type === 'string' && token.closed === false) {
        report(lineNo, 'error', 'PRL-L-STR', '字符串没有闭合的引号', token.start + 1)
      }
    }

    if (code.length === 0) {
      continue
    }

    // 缩进用 Tab：规则文件统一 4 空格，混用会让 diff 与对齐都难看
    if (/^\t/.test(raw) || /^ +\t/.test(raw)) {
      report(lineNo, 'warning', 'PRL-L-INDENT', '缩进用了 Tab，PRL 规则统一 4 空格')
    }

    const indent = raw.length - raw.trimStart().length
    const head = headKeyword(code)
    const isBlockHeader = PRL_BLOCK_HEADERS.includes(head)
    const isOpener = (isBlockHeader && code.endsWith(':')) || code.endsWith('{')

    // rule 声明
    let ruleOpenedThisLine: RuleState | null = null
    if (head === 'rule') {
      const rest = code.slice('rule'.length)
      const nameMatch = /^\s*"([^"]*)"/.exec(rest)
      if (!nameMatch) {
        report(lineNo, 'error', 'PRL-L-RULE', '规则名必须是字符串字面量，形如 rule "kill_aura_detection" {')
      } else {
        const name = nameMatch[1]
        const previous = ruleLines.get(name)
        if (previous !== undefined) {
          report(lineNo, 'warning', 'PRL-L-RULE', `规则名 "${name}" 在第 ${previous} 行已经声明过`)
        }
        ruleLines.set(name, lineNo)
        const state: RuleState = { name, line: lineNo, hasWhen: false, hasThen: false }
        rules.push(state)
        ruleOpenedThisLine = state
        currentRule = state
      }
      if (!rest.includes('{')) {
        report(lineNo, 'error', 'PRL-L-BRACE', 'rule 声明后面缺少 "{"')
      }
    }

    if (currentRule) {
      if (head === 'when') {
        currentRule.hasWhen = true
      } else if (head === 'then') {
        currentRule.hasThen = true
      }
    }

    // 块头冒号：when / then / if / else / for / while 都要以 ':' 收尾
    if (isBlockHeader && !code.endsWith(':')) {
      report(lineNo, 'error', 'PRL-L-COLON', `'${head}' 块头要以 ':' 结尾`)
    }

    // 块配对：if / for / while / else 由 end 收尾
    if (head === 'if' || head === 'for' || head === 'while') {
      blockStack.push({ kind: head, line: lineNo, indent })
    } else if (head === 'else') {
      const top = blockStack[blockStack.length - 1]
      if (!top || (top.kind !== 'if' && top.kind !== 'else')) {
        blockStack.push({ kind: 'else', line: lineNo, indent })
      }
    } else if (head === 'end') {
      if (blockStack.length === 0) {
        report(lineNo, 'warning', 'PRL-L-BLOCK', '多余的 end：没有与之配对的 if / for / while')
      } else {
        const top = blockStack.pop() as BlockEntry
        if (top.indent !== indent) {
          report(
            lineNo,
            'warning',
            'PRL-L-INDENT',
            `end 的缩进应与第 ${top.line} 行的开块行一致（期望 ${top.indent} 个空格，实际 ${indent}）`,
          )
        }
      }
    }

    // 相对缩进：开块行的下一行必须更深
    if (prevOpensBlock && indent <= prevIndent) {
      report(lineNo, 'warning', 'PRL-L-INDENT', '块体应比开块行缩进更深')
    }

    // 括号配对
    let consumedRuleBrace = false
    for (const token of tokens) {
      if (token.type !== 'punct') {
        continue
      }
      if (CLOSER[token.value]) {
        braceStack.push({
          ch: token.value,
          line: lineNo,
          rule: ruleOpenedThisLine && !consumedRuleBrace ? ruleOpenedThisLine : null,
        })
        if (ruleOpenedThisLine && !consumedRuleBrace) {
          consumedRuleBrace = true
        }
      } else if (token.value === ')' || token.value === ']' || token.value === '}') {
        const top = braceStack.pop()
        if (!top) {
          report(lineNo, 'error', 'PRL-L-BRACE', `多余的 "${token.value}"，没有与之配对的开括号`, token.start + 1)
        } else if (CLOSER[top.ch] !== token.value) {
          report(
            lineNo,
            'error',
            'PRL-L-BRACE',
            `括号不匹配：第 ${top.line} 行的 "${top.ch}" 对应的是 "${CLOSER[top.ch]}"，这里是 "${token.value}"`,
            token.start + 1,
          )
        } else if (top.rule) {
          const closed = top.rule
          if (!closed.hasWhen) {
            report(closed.line, 'warning', 'PRL-L-SECTION', `规则 "${closed.name}" 缺少 when: 条件段`)
          }
          if (!closed.hasThen) {
            report(closed.line, 'warning', 'PRL-L-SECTION', `规则 "${closed.name}" 缺少 then: 动作段`)
          }
          if (currentRule === closed) {
            currentRule = null
          }
        }
      }
    }

    // 未知函数：只认裸调用，`x.foo()` 是宿主对象的方法，不在白名单管辖范围内
    for (const token of tokens) {
      if (token.type !== 'function') {
        continue
      }
      if (PRL_STDLIB_NAMES.has(token.value) || hostFunctions.has(token.value) || declaredNames.has(token.value)) {
        continue
      }
      report(
        lineNo,
        'warning',
        'PRL-L-FUNC',
        `未知函数 ${token.value}(...)：不在标准库或宿主白名单中，编译期会被拦下`,
        token.start + 1,
      )
    }

    prevOpensBlock = isOpener
    prevIndent = indent
  }

  for (const entry of braceStack) {
    report(entry.line, 'error', 'PRL-L-BRACE', `括号未闭合：缺少 "${CLOSER[entry.ch]}"`)
  }

  for (const entry of blockStack) {
    report(entry.line, 'error', 'PRL-L-BLOCK', `'${entry.kind}' 块缺少配对的 end`)
  }

  return diagnostics.sort((a, b) => a.line - b.line || (a.column ?? 0) - (b.column ?? 0))
}

// ---------------------------------------------------------------------------
// 面板
// ---------------------------------------------------------------------------

type SourceFilter = 'all' | 'local' | 'server'
type ServerStatus = 'idle' | 'loading' | 'ready' | 'error'

interface ServerAnalysis {
  status: ServerStatus
  diagnostics: Diagnostic[]
  error?: string
  engineVersion?: string
  finishedAt?: number
}

export interface LinterProps extends PrlEndpointProps {
  source: string
  /** 当前编辑器行，列表里做高亮。 */
  activeLine?: number
  /** 点击问题：跳到编辑器对应行。 */
  onSelectLine?: (line: number, diagnostic: Diagnostic) => void
  hostFunctions?: readonly string[]
  /** 规则名，服务端做规则冲突检测（§2.14.2）用。 */
  ruleName?: string
  /** 服务端分析节流间隔，默认 800ms。 */
  serverThrottleMs?: number
  /** 关掉服务端分析，只保留本地检查。 */
  disableServerAnalysis?: boolean
  /** 把合并后的诊断抛给外部（编辑器在行号槽画标记用）。 */
  onDiagnostics?: (diagnostics: Diagnostic[]) => void
}

const SEVERITY_LABEL: Readonly<Record<DiagnosticSeverity, string>> = {
  error: '错误',
  warning: '警告',
  info: '提示',
}

const SEVERITY_TONE: Readonly<Record<DiagnosticSeverity, 'error' | 'warning' | 'neutral'>> = {
  error: 'error',
  warning: 'warning',
  info: 'neutral',
}

const NO_DIAGNOSTICS: Diagnostic[] = []

export function Linter({
  source,
  activeLine,
  onSelectLine,
  hostFunctions,
  ruleName,
  serverThrottleMs = 800,
  disableServerAnalysis = false,
  onDiagnostics,
  baseUrl,
  fetcher,
  requestTimeoutMs,
}: LinterProps) {
  const [filter, setFilter] = useState<SourceFilter>('all')
  const [server, setServer] = useState<ServerAnalysis>({ status: 'idle', diagnostics: [] })

  const endpoint = useMemo<PrlClientOptions>(
    () => ({ baseUrl, fetcher, requestTimeoutMs }),
    [baseUrl, fetcher, requestTimeoutMs],
  )
  const endpointReady = isPrlEndpointConfigured(endpoint)
  const serverEnabled = endpointReady && !disableServerAnalysis
  const hasSource = source.trim().length > 0

  const localDiagnostics = useMemo(() => lintSource(source, { hostFunctions }), [source, hostFunctions])

  // 源码清空后不再展示上一轮的服务端结果，避免出现「问题还在、源码没了」
  const serverDiagnostics = hasSource ? server.diagnostics : NO_DIAGNOSTICS

  const allDiagnostics = useMemo(() => {
    const merged = [...localDiagnostics, ...serverDiagnostics]
    return merged.sort((a, b) => a.line - b.line || (a.column ?? 0) - (b.column ?? 0))
  }, [localDiagnostics, serverDiagnostics])

  useEffect(() => {
    onDiagnostics?.(allDiagnostics)
  }, [allDiagnostics, onDiagnostics])

  useEffect(() => {
    if (!serverEnabled || !hasSource) {
      return
    }

    const controller = new AbortController()
    let cancelled = false
    const timer = setTimeout(() => {
      analyzeSource(endpoint, { source, ruleName }, controller.signal)
        .then((response) => {
          if (cancelled) {
            return
          }
          setServer({
            status: 'ready',
            diagnostics: diagnosticsFromServer(response),
            engineVersion: response.engineVersion,
            finishedAt: Date.now(),
          })
        })
        .catch((error: unknown) => {
          if (cancelled) {
            return
          }
          setServer({ status: 'error', diagnostics: [], error: describePrlError(error) })
        })
    }, serverThrottleMs)

    return () => {
      cancelled = true
      clearTimeout(timer)
      controller.abort()
    }
  }, [endpoint, hasSource, ruleName, serverEnabled, serverThrottleMs, source])

  const retryServerAnalysis = useCallback(() => {
    if (!serverEnabled || !hasSource) {
      return
    }
    setServer({ status: 'loading', diagnostics: [] })
    analyzeSource(endpoint, { source, ruleName })
      .then((response) => {
        setServer({
          status: 'ready',
          diagnostics: diagnosticsFromServer(response),
          engineVersion: response.engineVersion,
          finishedAt: Date.now(),
        })
      })
      .catch((error: unknown) => {
        setServer({ status: 'error', diagnostics: [], error: describePrlError(error) })
      })
  }, [endpoint, hasSource, ruleName, serverEnabled, source])

  const counts = useMemo(() => {
    let errors = 0
    let warnings = 0
    for (const item of allDiagnostics) {
      if (item.severity === 'error') {
        errors += 1
      } else if (item.severity === 'warning') {
        warnings += 1
      }
    }
    return { errors, warnings }
  }, [allDiagnostics])

  const visible = allDiagnostics.filter((item) => filter === 'all' || item.source === filter)

  return (
    <Panel
      title="实时检查"
      subtitle="本地即时检查会先跑，服务端分析随后覆盖；两者可分别筛选"
      index={1}
      actions={
        <div className="prl-linter__counts">
          <Badge tone={counts.errors > 0 ? 'error' : 'muted'}>错误 {counts.errors}</Badge>
          <Badge tone={counts.warnings > 0 ? 'warning' : 'muted'}>警告 {counts.warnings}</Badge>
        </div>
      }
    >
      <div className="prl-linter__filters" role="group" aria-label="诊断来源筛选">
        {(['all', 'local', 'server'] as SourceFilter[]).map((item) => (
          <button
            key={item}
            type="button"
            className={`prl-chip${filter === item ? ' prl-chip--on' : ''}`}
            onClick={() => setFilter(item)}
          >
            {item === 'all' ? '全部' : item === 'local' ? '本地检查' : '服务端分析'}
          </button>
        ))}
      </div>

      <div className="prl-linter__server">
        {!hasSource && <span className="prl-muted">等待源码…</span>}
        {hasSource && !endpointReady && (
          <span className="prl-muted">未接入后端（未提供 baseUrl / fetcher），只跑本地检查</span>
        )}
        {hasSource && endpointReady && disableServerAnalysis && (
          <span className="prl-muted">服务端分析已手动关闭，只跑本地检查</span>
        )}
        {hasSource && serverEnabled && server.status === 'loading' && <span className="prl-muted">服务端分析中…</span>}
        {hasSource && serverEnabled && server.status === 'idle' && <span className="prl-muted">服务端分析待触发</span>}
        {hasSource && serverEnabled && server.status === 'ready' && (
          <span className="prl-muted">
            服务端分析完成
            {server.engineVersion ? `（引擎 ${server.engineVersion}）` : ''}，共 {server.diagnostics.length} 条
          </span>
        )}
        {hasSource && serverEnabled && server.status === 'error' && <span className="prl-muted">服务端分析失败</span>}
        {serverEnabled && server.status === 'error' && (
          <button type="button" className="prl-btn prl-btn--ghost prl-btn--sm" onClick={retryServerAnalysis}>
            重试
          </button>
        )}
      </div>

      {server.status === 'error' && server.error && (
        <InlineError message={`服务端分析不可用：${server.error}`} />
      )}

      {!hasSource && <EmptyState title="还没有源码" hint="在编辑器里写点什么，这里会立刻给出本地检查结果。" />}

      {hasSource && visible.length === 0 && (
        <EmptyState
          title={filter === 'server' ? '服务端没有报出问题' : '本地检查未发现问题'}
          hint={
            serverEnabled && server.status !== 'ready'
              ? '服务端分析结果还没回来；类型、空指针、无限循环这些只有引擎能判定。'
              : '这只是本地结构检查，类型与安全问题仍需引擎确认。'
          }
        />
      )}

      {visible.length > 0 && (
        <ul className="prl-diag-list">
          {visible.map((item, i) => (
            <li key={`${item.source}-${item.line}-${item.code ?? ''}-${i}`}>
              <button
                type="button"
                className={`prl-diag${item.line === activeLine ? ' prl-diag--active' : ''}`}
                onClick={() => onSelectLine?.(item.line, item)}
              >
                <span className={`prl-diag__sev prl-diag__sev--${item.severity}`} aria-hidden="true" />
                <span className="prl-diag__line">L{item.line}</span>
                <span className="prl-diag__body">
                  <span className="prl-diag__message">{item.message}</span>
                  <span className="prl-diag__meta">
                    <Badge tone={SEVERITY_TONE[item.severity]}>{SEVERITY_LABEL[item.severity]}</Badge>
                    <Badge tone={item.source === 'server' ? 'gold' : 'neutral'}>
                      {item.source === 'server' ? '服务端' : '本地'}
                    </Badge>
                    {item.code && <span className="prl-mono prl-muted">{item.code}</span>}
                    {item.code && <span className="prl-muted">{describeDiagnosticCode(item.code)}</span>}
                  </span>
                  {item.hint && <span className="prl-diag__hint">{item.hint}</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}