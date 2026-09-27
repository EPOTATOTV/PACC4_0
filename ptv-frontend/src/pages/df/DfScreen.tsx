import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Empty, Tooltip } from 'antd'
import type { EChartsOption } from 'echarts'
import { VerticalAlignTopOutlined } from '@ant-design/icons'
import EChart from '../../components/EChart'
import { api } from '../../api/client'
import type {
  BiOverview, BiRealtime, DfStreamMetrics, DlStats, V52HighRiskPlayer,
} from '../../types'
import { gsap, motionAllowed, motionDuration } from '../../gsap'
import { useGSAP } from '../../hooks/useGSAP'

/**
 * DF §4.3.1 数据可视化大屏（验收 A22：首屏聚合加载 < 5s）。
 *
 * 数据来源全部为既有/已落地端点，不自造聚合接口：
 *  - /api/admin/bi/overview  一次拿到检测与红屏趋势、作弊类型分布、红屏健康（误报率）
 *  - /api/admin/bi/realtime  一次拿到在线数、近 1h/24h 检测与红屏量、待查验、最新红屏事件流
 *  - /api/admin/df/stream/metrics  流式检测延迟百分位（A18 观测面）
 *  - /api/admin/dl/stats     各平台客户端下载量（平台分布）
 *  - /api/admin/v52/profile/high-risk  高风险玩家
 *
 * 首屏 = 上述 5 个请求的「一轮 Promise.allSettled」（并发、不串行），任一失败只影响对应面板，
 * 其余面板照常渲染；之后按固定周期轮询保鲜，并显式展示「最后更新」与耗时以便观测 A22。
 */

/** 面板键：每个键对应屏幕上的一块，失败时只在这一块内联报错。 */
type PanelKey = 'stats' | 'trend' | 'platform' | 'cheat' | 'events' | 'players'

const POLL_MS = 15_000
const PALETTE = ['#58a6ff', '#d29922', '#ff4d3d', '#3fb950', '#a371f7', '#39c5cf', '#ffa940']
const LEVEL_COLOR: Record<string, string> = {
  HIGH: '#ff4d3d', CRITICAL: '#ff4d3d', MEDIUM: '#d29922', LOW: '#58a6ff', INFO: '#8b949e',
}

function fmtClock(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function fmtTime(ts?: string) {
  if (!ts) return '--:--:--'
  const t = ts.includes('T') ? ts.slice(11, 19) : ts.slice(0, 8)
  return t || '--:--:--'
}

function fmtNum(v?: number) {
  if (v === undefined || v === null || Number.isNaN(v)) return '—'
  return v.toLocaleString('zh-CN')
}

/** 通用面板外壳：标题 + 右角标 + 内联错误位（错误只占本块，不遮挡其它面板）。 */
function Panel({
  title, note, error, children,
}: {
  title: string
  note?: string
  error?: string
  children: ReactNode
}) {
  return (
    <section
      className="pacc-glass-md"
      data-in="panel"
      style={{
        display: 'flex', flexDirection: 'column', minHeight: 0, minWidth: 0,
        border: '1px solid var(--border)', background: 'var(--panel)',
        padding: '13px 15px 11px', gap: 9,
      }}
    >
      <header style={{ display: 'flex', alignItems: 'baseline', gap: 11, flex: '0 0 auto' }}>
        <span style={{
          fontSize: 12, letterSpacing: '.14em', textTransform: 'uppercase',
          color: 'var(--text)', opacity: .72,
        }}>
          {title}
        </span>
        <span style={{ flex: 1, height: 1, background: 'var(--border)' }} />
        {note && <span style={{ fontSize: 11, color: 'var(--muted)' }}>{note}</span>}
      </header>
      {error && (
        <div style={{
          flex: '0 0 auto', fontSize: 11.5, color: 'var(--red-2)',
          borderLeft: '2px solid var(--red-2)', paddingLeft: 9, lineHeight: 1.5,
        }}>
          面板不可用：{error}
        </div>
      )}
      <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {children}
      </div>
    </section>
  )
}

export default function DfScreen() {
  const navigate = useNavigate()

  const [overview, setOverview] = useState<BiOverview | null>(null)
  const [realtime, setRealtime] = useState<BiRealtime | null>(null)
  const [metrics, setMetrics] = useState<DfStreamMetrics | null>(null)
  const [dlStats, setDlStats] = useState<DlStats | null>(null)
  const [players, setPlayers] = useState<V52HighRiskPlayer[]>([])
  const [errs, setErrs] = useState<Partial<Record<PanelKey, string>>>({})
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const [costMs, setCostMs] = useState<number | null>(null)
  const [clock, setClock] = useState(() => new Date())

  const rootRef = useRef<HTMLDivElement>(null)

  // 单一聚合加载：5 个请求并发发出，allSettled 保证互不拖累；失败按面板归位。
  const load = useCallback(async () => {
    const started = performance.now()
    const [o, rt, m, dl, hr] = await Promise.allSettled([
      api.bi.overview(30),
      api.bi.realtime(),
      api.df.streamMetrics(),
      api.dl.stats(14),
      api.v52.profile.highRisk(20),
    ])
    const next: Partial<Record<PanelKey, string>> = {}
    const add = (key: PanelKey, raw: unknown) => {
      const msg = (raw as Error)?.message || '请求失败'
      next[key] = next[key] ? `${next[key]}；${msg}` : msg
    }

    if (o.status === 'fulfilled') setOverview(o.value)
    else { add('stats', o.reason); add('trend', o.reason); add('cheat', o.reason) }

    if (rt.status === 'fulfilled') setRealtime(rt.value)
    else { add('stats', rt.reason); add('events', rt.reason) }

    if (m.status === 'fulfilled') setMetrics(m.value)
    else add('stats', m.reason)

    if (dl.status === 'fulfilled') setDlStats(dl.value)
    else add('platform', dl.reason)

    if (hr.status === 'fulfilled') setPlayers(hr.value.players ?? [])
    else add('players', hr.reason)

    setErrs(next)
    if ([o, rt, m, dl, hr].some((r) => r.status === 'fulfilled')) {
      setLastUpdated(new Date())
      setCostMs(Math.round(performance.now() - started))
    }
  }, [])

  // 首屏加载 + 有界轮询：标签页隐藏时跳过，避免后台空转。
  useEffect(() => {
    void load()
    const timer = window.setInterval(() => { if (!document.hidden) void load() }, POLL_MS)
    const onVisible = () => { if (!document.hidden) void load() }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [load])

  // 顶部时钟：独立 1s 心跳，不触发任何请求。
  useEffect(() => {
    const id = window.setInterval(() => setClock(new Date()), 1000)
    return () => window.clearInterval(id)
  }, [])

  // 指针视差：背景层位移大、内容层位移小（≤2px），分层纵深；减弱动态下不接管。
  useEffect(() => {
    const el = rootRef.current
    if (!el || !motionAllowed()) return
    let raf = 0
    let queued = false
    const onMove = (e: MouseEvent) => {
      const x = (e.clientX / window.innerWidth - 0.5) * 2
      const y = (e.clientY / window.innerHeight - 0.5) * 2
      if (queued) return
      queued = true
      raf = requestAnimationFrame(() => {
        queued = false
        el.style.setProperty('--sx', x.toFixed(3))
        el.style.setProperty('--sy', y.toFixed(3))
      })
    }
    window.addEventListener('mousemove', onMove, { passive: true })
    return () => {
      window.removeEventListener('mousemove', onMove)
      cancelAnimationFrame(raf)
    }
  }, [])

  // 入场：顶栏先落位，KPI 条随后，六块面板最后；三段时长/缓动不一致并带负偏移，避免同速齐现。
  const gridRef = useGSAP<HTMLDivElement>(({ el }) => {
    const head = el.querySelectorAll('[data-in="head"]')
    const cards = el.querySelectorAll('[data-in="card"]')
    const panels = el.querySelectorAll('[data-in="panel"]')
    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })
    tl.from(head, { y: -10, opacity: 0, duration: motionDuration(0.34) })
    tl.from(cards, {
      y: 15, opacity: 0, duration: motionDuration(0.4),
      stagger: motionDuration(0.05), ease: 'power2.out', clearProps: 'all',
    }, '-=0.13')
    tl.from(panels, {
      y: 21, opacity: 0, duration: motionDuration(0.56),
      stagger: motionDuration(0.08), ease: 'power2.out', clearProps: 'all',
    }, '-=0.21')
  }, [])

  const health = overview?.redscreen_health
  const cards = useMemo(() => [
    { label: '在线客户端', value: fmtNum(realtime?.online), hint: 'WSS 在线会话数', accent: 'var(--kpi-green)' },
    { label: '近 24h 检测', value: fmtNum(realtime?.detection_last_24h), hint: `近 1h ${fmtNum(realtime?.detection_last_hour)} 条`, accent: 'var(--kpi-blue)' },
    { label: '近 24h 红屏', value: fmtNum(realtime?.redscreen_last_24h), hint: `待查验 ${fmtNum(realtime?.pending_inspect)} 条`, accent: 'var(--kpi-red)' },
    {
      label: '红屏误报率',
      value: health?.false_positive_rate === undefined ? '—' : `${(health.false_positive_rate * 100).toFixed(2)}%`,
      hint: `样本 ${fmtNum(health?.total)} 条`,
      accent: 'var(--kpi-amber)',
    },
    {
      label: '流式检测 P95',
      value: metrics?.p95Ms === undefined ? '—' : `${metrics.p95Ms} ms`,
      hint: metrics?.meanMs === undefined ? 'DF 流式引擎' : `均值 ${metrics.meanMs} ms · 处理 ${fmtNum(metrics.eventsProcessed)}`,
      accent: 'var(--kpi-muted)',
    },
  ], [realtime, health, metrics])

  const trendOption = useMemo<EChartsOption>(() => {
    const days = overview?.detection_trend?.days ?? []
    const detect = overview?.detection_trend?.counts ?? []
    const redAt = new Map((overview?.redscreen_trend?.days ?? []).map(
      (d, i) => [d, overview?.redscreen_trend?.counts?.[i] ?? 0],
    ))
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['检测事件', '红屏事件'], right: 0, top: 0, textStyle: { fontSize: 11, color: '#8b949e' } },
      grid: { left: 4, right: 8, top: 30, bottom: 2, containLabel: true },
      xAxis: {
        type: 'category',
        data: days.map((d) => d.slice(5)),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
        axisLabel: { color: '#8b949e', fontSize: 10.5 },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } },
        axisLabel: { color: '#8b949e', fontSize: 10.5 },
      },
      series: [
        {
          name: '检测事件', type: 'line', smooth: true, symbol: 'none', data: detect,
          lineStyle: { color: '#58a6ff', width: 2 }, areaStyle: { color: 'rgba(88,166,255,.12)' },
        },
        {
          name: '红屏事件', type: 'line', smooth: true, symbol: 'none',
          data: days.map((d) => redAt.get(d) ?? 0),
          lineStyle: { color: '#ff4d3d', width: 2 }, areaStyle: { color: 'rgba(255,77,61,.14)' },
        },
      ],
    }
  }, [overview])

  const platformOption = useMemo<EChartsOption>(() => {
    const rows = Object.entries(dlStats?.platform ?? {}).sort((a, b) => b[1] - a[1])
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, type: 'scroll', textStyle: { fontSize: 11, color: '#8b949e' } },
      series: [{
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '43%'],
        itemStyle: { borderRadius: 3, borderColor: '#101319', borderWidth: 2 },
        label: { color: '#98a0ae', fontSize: 10.5 },
        data: rows.map(([name, value], i) => ({
          name, value, itemStyle: { color: PALETTE[i % PALETTE.length] },
        })),
      }],
    }
  }, [dlStats])

  const cheatOption = useMemo<EChartsOption>(() => {
    const top = [...(overview?.cheat_types?.items ?? [])]
      .sort((a, b) => (b.count ?? 0) - (a.count ?? 0))
      .slice(0, 5)
      .reverse()
    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 4, right: 30, top: 6, bottom: 2, containLabel: true },
      xAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } },
        axisLabel: { color: '#8b949e', fontSize: 10.5 },
      },
      yAxis: {
        type: 'category',
        data: top.map((c) => c.cheat_type ?? '未知'),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
        axisLabel: { color: '#98a0ae', fontSize: 10.5 },
      },
      series: [{
        type: 'bar',
        barWidth: 11,
        data: top.map((c, i) => ({ value: c.count ?? 0, itemStyle: { color: PALETTE[i % PALETTE.length] } })),
        label: { show: true, position: 'right', fontSize: 10.5, color: '#98a0ae' },
      }],
    }
  }, [overview])

  const events = realtime?.recent_redscreens ?? []

  return (
    <div
      ref={rootRef}
      style={{
        position: 'relative', height: '100vh', overflow: 'hidden',
        background: 'var(--bg)', color: 'var(--text)',
        display: 'flex', flexDirection: 'column',
      }}
    >
      {/* 背景层：复用全站长周期漂移光斑，位移幅值最大，形成「背景 > 玻璃 > 文字」纵深 */}
      <div
        aria-hidden
        className="pacc-backdrop-a"
        style={{ transform: 'translate3d(calc(var(--sx, 0) * 12px), calc(var(--sy, 0) * 12px), 0)' }}
      />
      <div
        aria-hidden
        className="pacc-backdrop-b"
        style={{ transform: 'translate3d(calc(var(--sx, 0) * -7px), calc(var(--sy, 0) * -7px), 0)' }}
      />

      <div ref={gridRef} style={{
        position: 'relative', zIndex: 1, flex: 1, minHeight: 0,
        display: 'flex', flexDirection: 'column', padding: '18px 19px 16px', gap: 15,
        transform: 'translate3d(calc(var(--sx, 0) * 2px), calc(var(--sy, 0) * 2px), 0)',
      }}>
        {/* 顶栏：标题 + 实时时钟 + 保鲜信息（最后更新 / 耗时 / 刷新周期） */}
        <header data-in="head" style={{
          flex: '0 0 auto', display: 'flex', alignItems: 'baseline', gap: 14, flexWrap: 'wrap',
        }}>
          <h1 style={{ margin: 0, fontSize: 21, letterSpacing: '.05em' }}>PACC 实时检测大屏</h1>
          <span style={{
            fontSize: 12, letterSpacing: '.18em', textTransform: 'uppercase', color: 'var(--text)', opacity: .55,
          }}>
            Deep Fortress · Alpha 1.0.0
          </span>
          <span style={{ flex: 1, height: 1, background: 'var(--border)', alignSelf: 'center' }} />
          <span className="mono" style={{ fontSize: 15, color: 'var(--text)', opacity: .9 }}>{fmtClock(clock)}</span>
          <Tooltip title={`刷新周期 ${POLL_MS / 1000}s`}>
            <span className="mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>
              最后更新 {lastUpdated ? fmtClock(lastUpdated) : '连接中…'}
              {costMs !== null && ` · 耗时 ${costMs} ms`}
            </span>
          </Tooltip>
          <Button size="small" type="text" onClick={() => void load()}>立即刷新</Button>
          <Button size="small" type="text" icon={<VerticalAlignTopOutlined />} onClick={() => navigate('/')}>
            退出大屏
          </Button>
        </header>

        {/* 主体：3 列 × 2 行（上排统计卡片 / 趋势 / 平台，下排 TOP5 / 事件流 / 高风险） */}
        <div style={{
          flex: 1, minHeight: 0,
          display: 'grid', gap: 15,
          gridTemplateColumns: 'minmax(0, 1.02fr) minmax(0, 1.5fr) minmax(0, 1fr)',
          gridTemplateRows: 'auto minmax(0, 1fr)',
        }}>
          {/* 统计卡片：一块整面板内用发丝分隔的 5 段文本块，不做 5 张同构卡片 */}
          <section
            className="pacc-glass-lg"
            data-in="panel"
            style={{
              gridColumn: '1 / 2', gridRow: '1 / 2',
              border: '1px solid var(--border)', background: 'var(--panel)',
              padding: '14px 15px 12px', display: 'flex', flexDirection: 'column', gap: 10,
            }}
          >
            <header style={{ display: 'flex', alignItems: 'baseline', gap: 11 }}>
              <span style={{ fontSize: 12, letterSpacing: '.14em', textTransform: 'uppercase', color: 'var(--text)', opacity: .72 }}>
                核心指标
              </span>
              <span style={{ flex: 1, height: 1, background: 'var(--border)' }} />
            </header>
            {errs.stats && (
              <div style={{ fontSize: 11.5, color: 'var(--red-2)', borderLeft: '2px solid var(--red-2)', paddingLeft: 9 }}>
                面板不可用：{errs.stats}
              </div>
            )}
            <div style={{
              flex: 1, minHeight: 0,
              display: 'grid', gridTemplateColumns: 'repeat(5, minmax(0, 1fr))',
            }}>
              {cards.map((c, i) => (
                <div
                  key={c.label}
                  data-in="card"
                  style={{
                    padding: i === 0 ? '2px 10px 2px 0' : '2px 10px',
                    borderLeft: i === 0 ? 'none' : '1px solid var(--border)',
                    display: 'flex', flexDirection: 'column', gap: 5,
                  }}
                >
                  <span style={{ fontSize: 11, letterSpacing: '.06em', color: 'var(--muted)', lineHeight: 1.3 }}>
                    {c.label}
                  </span>
                  <span className="mono" style={{
                    fontSize: 25, fontWeight: 700, lineHeight: 1.15, color: c.accent,
                    fontVariantNumeric: 'tabular-nums',
                  }}>
                    {c.value}
                  </span>
                  <span style={{ fontSize: 10.5, color: 'var(--dim)', lineHeight: 1.35 }}>{c.hint}</span>
                </div>
              ))}
            </div>
          </section>

          <div style={{ gridColumn: '2 / 3', gridRow: '1 / 2', minWidth: 0, display: 'flex' }}>
            <Panel
              title="检测 / 红屏趋势"
              note={`按日 · 近 ${overview?.detection_trend?.days?.length ?? 30} 天`}
              error={errs.trend}
            >
              {overview
                ? <div style={{ flex: 1, minHeight: 132 }}><EChart option={trendOption} height="100%" /></div>
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无趋势数据" />}
            </Panel>
          </div>

          <div style={{ gridColumn: '3 / 4', gridRow: '1 / 2', minWidth: 0, display: 'flex' }}>
            <Panel title="平台分布" note={`近 ${dlStats?.days ?? 14} 天下载量`} error={errs.platform}>
              {dlStats && Object.keys(dlStats.platform ?? {}).length > 0
                ? <div style={{ flex: 1, minHeight: 132 }}><EChart option={platformOption} height="100%" /></div>
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无平台数据" />}
            </Panel>
          </div>

          <div style={{ gridColumn: '1 / 2', gridRow: '2 / 3', minWidth: 0, display: 'flex' }}>
            <Panel title="作弊类型 TOP5" note="近 7 天红屏归因" error={errs.cheat}>
              {(overview?.cheat_types?.items?.length ?? 0) > 0
                ? <div style={{ flex: 1, minHeight: 120 }}><EChart option={cheatOption} height="100%" /></div>
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无归因数据" />}
            </Panel>
          </div>

          {/* 实时事件流：文本 + 发丝分隔行，不做卡片堆叠 */}
          <div style={{ gridColumn: '2 / 3', gridRow: '2 / 3', minWidth: 0, display: 'flex' }}>
            <Panel
              title="实时事件流"
              note={`最新 ${events.length} 条红屏`}
              error={errs.events}
            >
              {events.length === 0
                ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="窗口内暂无事件" />
                : (
                  <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                    {events.slice(0, 25).map((e, i) => (
                      <div
                        key={`${e.alertId ?? 'ev'}-${i}`}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 11,
                          padding: '7px 2px', borderBottom: '1px solid var(--border)',
                        }}
                      >
                        <span className="mono" style={{ fontSize: 11, color: 'var(--muted)', flex: '0 0 66px' }}>
                          {fmtTime(e.occurredAt)}
                        </span>
                        <span style={{
                          flex: '0 0 auto', fontSize: 10.5, padding: '1px 7px', lineHeight: 1.6,
                          border: `1px solid ${LEVEL_COLOR[e.level ?? ''] ?? 'var(--border)'}`,
                          color: LEVEL_COLOR[e.level ?? ''] ?? 'var(--muted)',
                        }} className="pacc-glass-sm">
                          {e.level ?? '—'}
                        </span>
                        <span style={{ flex: 1, minWidth: 0, fontSize: 12.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {e.cheatType ?? '未分类'}
                        </span>
                        <span style={{ flex: '0 0 auto', fontSize: 11, color: 'var(--muted)' }}>{e.state ?? ''}</span>
                      </div>
                    ))}
                  </div>
                )}
            </Panel>
          </div>

          <div style={{ gridColumn: '3 / 4', gridRow: '2 / 3', minWidth: 0, display: 'flex' }}>
            <Panel title="高风险玩家" note={`信誉最低 ${players.length} 人`} error={errs.players}>
              {players.length === 0
                ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无高风险玩家" />
                : (
                  <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                    {players.slice(0, 20).map((p, i) => (
                      <div
                        key={`${p.pteid ?? 'p'}-${i}`}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 10,
                          padding: '7px 2px', borderBottom: '1px solid var(--border)',
                        }}
                      >
                        <span className="mono" style={{ fontSize: 12, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {p.pteid ?? '—'}
                        </span>
                        <span className="mono" style={{
                          fontSize: 13, fontWeight: 700, flex: '0 0 auto',
                          color: (p.reputation_score ?? 0) < 300 ? 'var(--kpi-red)' : 'var(--kpi-amber)',
                        }}>
                          {fmtNum(p.reputation_score)}
                        </span>
                        <span style={{ flex: '0 0 58px', fontSize: 10.5, color: 'var(--muted)', textAlign: 'right' }}>
                          {p.reputation_level ?? '—'}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
            </Panel>
          </div>
        </div>
      </div>
    </div>
  )
}