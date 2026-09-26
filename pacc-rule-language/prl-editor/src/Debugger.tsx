/**
 * 调试界面（§2.15.1）。
 *
 * 这里不实现真正的调试协议：请求函数都在 api.ts 里（`/api/prl/debug/...`），
 * 本组件只负责把断点表、单步按钮、当前行、局部变量、调用栈画出来。
 * 没接后端时可以用前端演示数据走一遍界面，演示态在 UI 上明确标记，不冒充真实结果。
 */
import { useCallback, useMemo, useState } from 'react'
import {
  createDebugSession,
  describePrlError,
  isPrlEndpointConfigured,
  resumeDebug,
  stepDebug,
  stopDebug,
  type PrlClientOptions,
} from './api'
import { codeOf, extractDeclarations, tokenizeLine } from './prlLanguage'
import type { Breakpoint, DebugAction, DebugLogEntry, DebugState, DebugVariable, PrlEndpointProps } from './types'
import { Badge, DemoMark, EmptyState, InlineError, Panel } from './ui'

const LINE_HEIGHT = 20
const DEMO_VALUE = '(演示) 未求值'
const MAX_OUTPUT = 24

export interface DebuggerProps extends PrlEndpointProps {
  source: string
  ruleName?: string
  ruleVersion?: string
  /** 受控断点；不传则组件内部维护。 */
  breakpoints?: Breakpoint[]
  onBreakpointsChange?: (next: Breakpoint[]) => void
  /** 受控调试状态；不传则组件内部维护。 */
  state?: DebugState
  /** 动作回调：父组件接管后，本组件不再调用 api，也不走演示逻辑。 */
  onAction?: (action: DebugAction) => void
}

/** 可执行行：去掉空行、纯注释、`}` 与 `rule` 声明行。 */
function executableLines(source: string): number[] {
  const out: number[] = []
  source.split(/\r?\n/).forEach((raw, index) => {
    const code = codeOf(raw)
    if (code.length === 0 || code === '}' || code.startsWith('}') || /^rule\b/.test(code)) {
      return
    }
    out.push(index + 1)
  })
  return out
}

function demoVariables(source: string): DebugVariable[] {
  return extractDeclarations(source).map((declaration) => ({
    name: declaration.name,
    type: declaration.type ?? (declaration.kind === 'let' ? '（推断）' : 'any'),
    value: DEMO_VALUE,
    scope: declaration.kind === 'input' ? 'input' : 'local',
  }))
}

function demoState(source: string, ruleName: string, ruleVersion: string, line: number): DebugState {
  const label = ruleName || '未命名规则'
  return {
    sessionId: 'demo-session',
    ruleName: label,
    ruleVersion,
    status: 'paused',
    currentLine: line,
    variables: demoVariables(source),
    callStack: [{ id: 'frame-0', name: `rule ${label}`, line }],
    output: [
      {
        seq: 1,
        line,
        level: 'info',
        message: `演示会话已创建，暂停在第 ${line} 行（前端模拟，非引擎结果）`,
      },
    ],
    isDemo: true,
  }
}

function appendOutput(output: DebugLogEntry[], line: number, message: string): DebugLogEntry[] {
  const seq = (output[output.length - 1]?.seq ?? 0) + 1
  const next = [...output, { seq, line, level: 'info' as const, message }]
  return next.slice(-MAX_OUTPUT)
}

export function Debugger({
  source,
  ruleName = '',
  ruleVersion = '',
  breakpoints: controlledBreakpoints,
  onBreakpointsChange,
  state: controlledState,
  onAction,
  baseUrl,
  fetcher,
  requestTimeoutMs,
}: DebuggerProps) {
  const [ownedBreakpoints, setOwnedBreakpoints] = useState<Breakpoint[]>([])
  const [ownedState, setOwnedState] = useState<DebugState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const endpoint = useMemo<PrlClientOptions>(
    () => ({ baseUrl, fetcher, requestTimeoutMs }),
    [baseUrl, fetcher, requestTimeoutMs],
  )
  const endpointReady = isPrlEndpointConfigured(endpoint)
  const breakpoints = controlledBreakpoints ?? ownedBreakpoints
  const debugState = controlledState ?? ownedState
  const lines = useMemo(() => source.split(/\r?\n/), [source])
  const steppable = useMemo(() => executableLines(source), [source])

  const breakpointLines = useMemo(() => new Set(breakpoints.map((bp) => bp.line)), [breakpoints])

  const setBreakpoints = useCallback(
    (next: Breakpoint[]) => {
      setOwnedBreakpoints(next)
      onBreakpointsChange?.(next)
    },
    [onBreakpointsChange],
  )

  const toggleBreakpoint = useCallback(
    (line: number) => {
      const existing = breakpoints.find((bp) => bp.line === line)
      if (existing) {
        setBreakpoints(breakpoints.filter((bp) => bp.line !== line))
        return
      }
      setBreakpoints([
        ...breakpoints,
        {
          id: `${ruleName || 'rule'}:${line}`,
          ruleName: ruleName || '未命名规则',
          line,
          enabled: true,
        },
      ])
    },
    [breakpoints, ruleName, setBreakpoints],
  )

  const removeBreakpoint = useCallback(
    (id: string) => {
      setBreakpoints(breakpoints.filter((bp) => bp.id !== id))
    },
    [breakpoints, setBreakpoints],
  )

  const toggleBreakpointEnabled = useCallback(
    (id: string) => {
      setBreakpoints(breakpoints.map((bp) => (bp.id === id ? { ...bp, enabled: !bp.enabled } : bp)))
    },
    [breakpoints, setBreakpoints],
  )

  /** 无后端时的前端模拟：按可执行行往下走，变量值一律标记为未求值。 */
  const runDemo = useCallback(
    (action: DebugAction) => {
      setError(null)
      if (action.type === 'stop') {
        setOwnedState((previous) =>
          previous
            ? { ...previous, status: 'stopped', sessionId: '', output: appendOutput(previous.output, previous.currentLine, '演示会话已停止') }
            : previous,
        )
        return
      }
      if (action.type === 'start') {
        const first = steppable[0] ?? 1
        setOwnedState(demoState(source, ruleName, ruleVersion, first))
        return
      }
      setOwnedState((previous) => {
        const current = previous ?? demoState(source, ruleName, ruleVersion, steppable[0] ?? 1)
        if (current.status === 'stopped' || current.status === 'idle') {
          return current
        }
        if (action.type === 'continue') {
          const last = steppable[steppable.length - 1] ?? current.currentLine
          return {
            ...current,
            status: 'stopped',
            currentLine: last,
            callStack: current.callStack.map((frame) => ({ ...frame, line: last })),
            output: appendOutput(current.output, last, `继续执行到第 ${last} 行，演示会话结束`),
          }
        }
        const index = steppable.indexOf(current.currentLine)
        const nextLine = steppable[Math.min(index + 1, steppable.length - 1)] ?? current.currentLine
        const verb = action.type === 'stepInto' ? '单步进入' : '单步跳过'
        return {
          ...current,
          status: 'paused',
          currentLine: nextLine,
          callStack: current.callStack.map((frame) => ({ ...frame, line: nextLine })),
          output: appendOutput(current.output, nextLine, `${verb}到第 ${nextLine} 行`),
        }
      })
    },
    [ruleName, ruleVersion, source, steppable],
  )

  const callServer = useCallback(
    async (action: DebugAction) => {
      setBusy(true)
      setError(null)
      try {
        if (action.type === 'start') {
          const response = await createDebugSession(endpoint, {
            ruleName: ruleName || '未命名规则',
            source,
            ruleVersion: ruleVersion || undefined,
            breakpoints,
          })
          setOwnedState(response.state)
          return
        }
        const sessionId = debugState?.sessionId ?? ''
        if (!sessionId) {
          setError('没有活动会话：先点「开始会话」。')
          return
        }
        if (action.type === 'stepInto') {
          const response = await stepDebug(endpoint, { sessionId, mode: 'into' })
          setOwnedState(response.state)
          return
        }
        if (action.type === 'stepOver') {
          const response = await stepDebug(endpoint, { sessionId, mode: 'over' })
          setOwnedState(response.state)
          return
        }
        if (action.type === 'continue') {
          const response = await resumeDebug(endpoint, { sessionId })
          setOwnedState(response.state)
          return
        }
        const response = await stopDebug(endpoint, { sessionId })
        setOwnedState(response.state)
      } catch (caught) {
        setError(`调试接口调用失败：${describePrlError(caught)}`)
      } finally {
        setBusy(false)
      }
    },
    [breakpoints, debugState, endpoint, ruleName, ruleVersion, source],
  )

  const runAction = useCallback(
    (action: DebugAction) => {
      if (onAction) {
        onAction(action)
        return
      }
      if (endpointReady) {
        void callServer(action)
        return
      }
      runDemo(action)
    },
    [callServer, endpointReady, onAction, runDemo],
  )

  const status = debugState?.status ?? 'idle'
  const sessionActive = Boolean(debugState?.sessionId) && (status === 'paused' || status === 'running')
  const canStart = !busy && status !== 'paused' && status !== 'running'

  const statusTone = status === 'paused' ? 'gold' : status === 'error' ? 'error' : status === 'running' ? 'ok' : 'muted'
  const statusLabel =
    status === 'idle' ? '未开始' : status === 'running' ? '运行中' : status === 'paused' ? '已暂停' : status === 'stopped' ? '已停止' : '出错'

  const frameStyle = { height: LINE_HEIGHT, lineHeight: `${LINE_HEIGHT}px` }

  return (
    <div className="prl-debug">
      <Panel
        title="断点"
        subtitle={`${breakpoints.length} 个断点${ruleName ? ` · ${ruleName}` : ''}`}
        index={0}
        actions={<Badge tone={statusTone}>{statusLabel}</Badge>}
      >
        <div className="prl-debug__controls">
          <button
            type="button"
            className="prl-btn"
            disabled={!canStart || busy}
            onClick={() => runAction({ type: 'start' })}
          >
            开始会话
          </button>
          <button
            type="button"
            className="prl-btn prl-btn--ghost"
            disabled={!sessionActive || busy}
            onClick={() => runAction({ type: 'stepOver' })}
          >
            单步跳过
          </button>
          <button
            type="button"
            className="prl-btn prl-btn--ghost"
            disabled={!sessionActive || busy}
            onClick={() => runAction({ type: 'stepInto' })}
          >
            单步进入
          </button>
          <button
            type="button"
            className="prl-btn prl-btn--ghost"
            disabled={!sessionActive || busy}
            onClick={() => runAction({ type: 'continue' })}
          >
            继续
          </button>
          <button
            type="button"
            className="prl-btn prl-btn--ghost"
            disabled={!sessionActive || busy}
            onClick={() => runAction({ type: 'stop' })}
          >
            停止
          </button>
          {busy && <span className="prl-muted">请求中…</span>}
        </div>

        {!endpointReady && !onAction && (
          <p className="prl-muted prl-debug__note">
            未接入后端：点「开始会话」会用前端演示数据走一遍界面，不会真的驱动引擎。
          </p>
        )}
        {debugState?.isDemo && (
          <p className="prl-debug__note">
            <DemoMark /> 单步由前端模拟，变量值没有真正求值。
          </p>
        )}
        {error && <InlineError message={error} />}

        {breakpoints.length === 0 ? (
          <EmptyState title="没有断点" hint="点右侧源码行号槽可以加断点（规则名 + 行号）。" />
        ) : (
          <ul className="prl-bp-list">
            {breakpoints.map((bp) => (
              <li key={bp.id} className={`prl-bp${bp.line === debugState?.currentLine ? ' prl-bp--active' : ''}`}>
                <input
                  type="checkbox"
                  checked={bp.enabled}
                  onChange={() => toggleBreakpointEnabled(bp.id)}
                  aria-label={`启用第 ${bp.line} 行的断点`}
                />
                <span className="prl-bp__rule">{bp.ruleName}</span>
                <span className="prl-mono prl-bp__line">L{bp.line}</span>
                {bp.condition && <span className="prl-muted">条件：{bp.condition}</span>}
                <button type="button" className="prl-btn prl-btn--ghost prl-btn--sm" onClick={() => removeBreakpoint(bp.id)}>
                  移除
                </button>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <div className="prl-debug__stack">
        <Panel
          title="局部变量"
          subtitle={debugState ? `当前行 L${debugState.currentLine}` : '等待会话'}
          index={1}
        >
          {!debugState ? (
            <EmptyState title="没有会话数据" hint="开始会话后这里显示 input 字段与 let 局部变量的值。" />
          ) : debugState.variables.length === 0 ? (
            <EmptyState title="这条规则没有变量" hint="input 段与 let 声明都为空。" />
          ) : (
            <table className="prl-table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>类型 / 来源</th>
                  <th>值</th>
                </tr>
              </thead>
              <tbody>
                {debugState.variables.map((variable) => (
                  <tr key={variable.name}>
                    <td className="prl-mono">{variable.name}</td>
                    <td>
                      <Badge tone={variable.scope === 'input' ? 'gold' : 'neutral'}>{variable.scope}</Badge>
                      <span className="prl-muted prl-mono"> {variable.type}</span>
                    </td>
                    <td className="prl-mono prl-var-value">{variable.value || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>

        <Panel title="调用栈" subtitle="PRL 只有规则级与 lambda 两级" index={2}>
          {!debugState || debugState.callStack.length === 0 ? (
            <EmptyState title="调用栈为空" hint="暂停时这里会列出当前栈帧。" />
          ) : (
            <ol className="prl-stack">
              {debugState.callStack.map((frame) => (
                <li key={frame.id}>
                  <span className="prl-mono">{frame.name}</span>
                  <span className="prl-muted prl-mono">L{frame.line}</span>
                </li>
              ))}
            </ol>
          )}
        </Panel>

        <Panel title="执行日志" subtitle="断点命中与日志点的输出" index={3}>
          {!debugState || debugState.output.length === 0 ? (
            <EmptyState title="暂无输出" hint="会话开始后，这里按顺序记录。" />
          ) : (
            <ul className="prl-log">
              {debugState.output.map((entry) => (
                <li key={entry.seq} className={`prl-log__item prl-log__item--${entry.level}`}>
                  <span className="prl-muted prl-mono">L{entry.line}</span>
                  <span>{entry.message}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      <Panel title="源码" subtitle="点行号槽切换断点" index={4} className="prl-debug__source">
        {lines.length === 0 || source.trim().length === 0 ? (
          <EmptyState title="没有源码" hint="从编辑器把源码带过来，或在 props 里传 source。" />
        ) : (
          <div className="prl-sourceview">
            {lines.map((raw, index) => {
              const lineNo = index + 1
              const isCurrent = lineNo === debugState?.currentLine
              const hasBreakpoint = breakpointLines.has(lineNo)
              return (
                <div key={lineNo} className={`prl-sourceview__row${isCurrent ? ' prl-sourceview__row--current' : ''}`} style={frameStyle}>
                  <button
                    type="button"
                    className={`prl-sourceview__gutter${hasBreakpoint ? ' prl-sourceview__gutter--bp' : ''}`}
                    onClick={() => toggleBreakpoint(lineNo)}
                    title={hasBreakpoint ? `移除第 ${lineNo} 行断点` : `在第 ${lineNo} 行加断点`}
                  >
                    <span className="prl-sourceview__dot" aria-hidden="true" />
                    {lineNo}
                  </button>
                  <code className="prl-sourceview__code">
                    {tokenizeLine(raw).map((token, tokenIndex) => (
                      <span key={tokenIndex} className={`prl-tok prl-tok--${token.type}`}>
                        {token.value}
                      </span>
                    ))}
                  </code>
                </div>
              )
            })}
          </div>
        )}
      </Panel>
    </div>
  )
}