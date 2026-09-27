/**
 * 性能分析界面（§2.15.2）。
 *
 * 数据形状就是文档里 Profiler 的输出：执行次数、平均/P95/P99/最大耗时、内存峰值、
 * 热点函数占比、优化建议。数据来源优先级：props 注入 > 后端接口 > 前端演示数据（明确标记）。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { describePrlError, fetchRuleProfile, isPrlEndpointConfigured, type PrlClientOptions } from './api'
import type { PrlEndpointProps, RuleProfile } from './types'
import { Badge, DemoMark, EmptyState, InlineError, Panel } from './ui'

export interface ProfilerProps extends PrlEndpointProps {
  /** 直接注入的分析结果；给了就不再请求后端。 */
  profile?: RuleProfile
  ruleName?: string
  ruleVersion?: string
  /** 采样窗口，如 `1h` / `24h`，透传给后端。 */
  window?: string
  loading?: boolean
  /** 外部请求失败时的错误文案。 */
  error?: string
  onRefresh?: () => void
}

type Status = 'idle' | 'loading' | 'ready' | 'error'

const INT_FORMAT = new Intl.NumberFormat('en-US')

function formatMs(value: number): string {
  return `${value.toFixed(2)}ms`
}

function formatInt(value: number): string {
  return INT_FORMAT.format(value)
}

/** 前端内置的演示结果，数值取自设计文档 §2.15.2 的示例。 */
function createDemoProfile(ruleName: string, ruleVersion: string): RuleProfile {
  return {
    ruleName: ruleName || 'kill_aura_detection',
    ruleVersion: ruleVersion || '1.2.0',
    executions: 1234567,
    avgMs: 0.23,
    p95Ms: 0.45,
    p99Ms: 0.89,
    maxMs: 2.34,
    memoryPeakKb: 12,
    window: '演示窗口（前端内置，非真实采样）',
    hotspots: [
      { name: 'analyze_click_pattern', percent: 45, suggestion: '输入未变时可缓存结果' },
      { name: 'rate', percent: 20, suggestion: '可改用滑动窗口增量计算' },
      { name: 'filter', percent: 15 },
      { name: 'accuracy', percent: 10 },
      { name: '其他', percent: 10 },
    ],
    suggestions: [
      'analyze_click_pattern 可缓存结果（输入未变时）',
      'rate 函数可增量计算（滑动窗口）',
      'filter + map 连续两次遍历可以合并成一次',
    ],
  }
}

export function Profiler({
  profile,
  ruleName = '',
  ruleVersion = '',
  window: profileWindow,
  loading = false,
  error: externalError,
  onRefresh,
  baseUrl,
  fetcher,
  requestTimeoutMs,
}: ProfilerProps) {
  const [fetched, setFetched] = useState<RuleProfile | null>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [demo, setDemo] = useState<RuleProfile | null>(null)
  const [reloadSeq, setReloadSeq] = useState(0)

  const endpoint = useMemo<PrlClientOptions>(
    () => ({ baseUrl, fetcher, requestTimeoutMs }),
    [baseUrl, fetcher, requestTimeoutMs],
  )
  const endpointReady = isPrlEndpointConfigured(endpoint)
  const canFetch = endpointReady && !profile && ruleName.length > 0

  useEffect(() => {
    if (!canFetch) {
      return
    }
    const controller = new AbortController()
    let cancelled = false
    fetchRuleProfile(endpoint, { ruleName, version: ruleVersion || undefined, window: profileWindow }, controller.signal)
      .then((response) => {
        if (cancelled) {
          return
        }
        setFetched(response)
        setStatus('ready')
        setError(null)
      })
      .catch((caught: unknown) => {
        if (cancelled) {
          return
        }
        setFetched(null)
        setStatus('error')
        setError(describePrlError(caught))
      })
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [canFetch, endpoint, profileWindow, reloadSeq, ruleName, ruleVersion])

  const refresh = useCallback(() => {
    onRefresh?.()
    if (canFetch) {
      setStatus('loading')
    }
    setReloadSeq((seq) => seq + 1)
  }, [canFetch, onRefresh])

  const active = profile ?? demo ?? fetched
  // 首次请求期间 status 还是 idle，这里一并算作加载中；effect 里不再同步 setState。
  const busy = loading || status === 'loading' || (canFetch && status === 'idle')
  const errorMessage = externalError ?? error
  const sourceLabel = profile ? 'props 注入' : demo ? '前端演示' : fetched ? '后端接口' : '无数据'

  return (
    <Panel
      title="性能分析"
      subtitle={active ? `${active.ruleName} v${active.ruleVersion}${active.window ? ` · ${active.window}` : ''}` : '没有可展示的采样'}
      index={0}
      actions={
        <div className="prl-profiler__actions">
          <Badge tone={demo ? 'gold' : 'muted'}>{sourceLabel}</Badge>
          <button type="button" className="prl-btn prl-btn--ghost prl-btn--sm" onClick={refresh} disabled={busy}>
            刷新
          </button>
        </div>
      }
    >
      {demo && (
        <p className="prl-muted prl-profiler__note">
          <DemoMark /> 上面这组数字是前端内置的示例（取自设计文档 §2.15.2），不代表任何真实运行结果。
        </p>
      )}
      {busy && <p className="prl-muted">正在拉取采样…</p>}
      {errorMessage && <InlineError message={`性能数据获取失败：${errorMessage}`} />}

      {!active && !busy && !errorMessage && (
        <EmptyState
          title="没有性能数据"
          hint={
            endpointReady
              ? '后端还没返回这条规则的采样；确认规则已在跑，或稍后再刷新。'
              : '未接入后端（未提供 baseUrl / fetcher），也没有通过 props 注入 profile。'
          }
          action={
            <button
              type="button"
              className="prl-btn prl-btn--ghost prl-btn--sm"
              onClick={() => setDemo(createDemoProfile(ruleName, ruleVersion))}
            >
              载入演示数据
            </button>
          }
        />
      )}

      {active && (
        <>
          <div className="prl-metrics">
            <div className="prl-metric">
              <span className="prl-metric__label">执行次数</span>
              <span className="prl-metric__value prl-mono">{formatInt(active.executions)}</span>
            </div>
            <div className="prl-metric">
              <span className="prl-metric__label">平均耗时</span>
              <span className="prl-metric__value prl-mono">{formatMs(active.avgMs)}</span>
            </div>
            <div className="prl-metric">
              <span className="prl-metric__label">P95</span>
              <span className="prl-metric__value prl-mono">{formatMs(active.p95Ms)}</span>
            </div>
            <div className="prl-metric">
              <span className="prl-metric__label">P99</span>
              <span className="prl-metric__value prl-mono">{formatMs(active.p99Ms)}</span>
            </div>
            <div className="prl-metric">
              <span className="prl-metric__label">最大耗时</span>
              <span className="prl-metric__value prl-mono">{formatMs(active.maxMs)}</span>
            </div>
            <div className="prl-metric">
              <span className="prl-metric__label">内存峰值</span>
              <span className="prl-metric__value prl-mono">{active.memoryPeakKb}KB</span>
            </div>
          </div>

          <h3 className="prl-section-title">热点函数</h3>
          {active.hotspots.length === 0 ? (
            <EmptyState title="没有热点数据" hint="采样里还没收集到函数级耗时。" />
          ) : (
            <ul className="prl-hotspots">
              {active.hotspots.map((hotspot) => (
                <li key={hotspot.name} className="prl-hotspot">
                  <span className="prl-hotspot__name prl-mono">{hotspot.name}</span>
                  <span className="prl-hotspot__bar">
                    <span className="prl-hotspot__fill" style={{ width: `${Math.max(0, Math.min(100, hotspot.percent))}%` }} />
                  </span>
                  <span className="prl-hotspot__percent prl-mono">{hotspot.percent}%</span>
                  {hotspot.totalMs !== undefined && (
                    <span className="prl-muted prl-mono">{formatMs(hotspot.totalMs)}</span>
                  )}
                  {hotspot.suggestion && <span className="prl-hotspot__tip">{hotspot.suggestion}</span>}
                </li>
              ))}
            </ul>
          )}

          <h3 className="prl-section-title">优化建议</h3>
          {active.suggestions.length === 0 ? (
            <EmptyState title="暂时没有建议" hint="没有热点或采样不足时不会有建议。" />
          ) : (
            <ul className="prl-tips">
              {active.suggestions.map((tip, index) => (
                <li key={`${tip}-${index}`}>{tip}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </Panel>
  )
}