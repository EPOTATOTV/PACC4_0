import { useEffect, useMemo, useState } from 'react'
import type { EChartsOption } from 'echarts'
import { api } from '../api/client'
import type { CheatTypeCount, StatsSummary, TrendPoint } from '../types'
import EChart from '../components/EChart'

function Metric({ label, value, accent }: { label: string; value: number | string; accent?: string }) {
  return (
    <div className="card" style={{ flex: '1 1 200px' }}>
      <div style={{ fontSize: 30, fontWeight: 700, color: accent ?? '#58a6ff' }}>{value}</div>
      <div style={{ color: '#8b949e', fontSize: 13, marginTop: 4 }}>{label}</div>
    </div>
  )
}

export default function Dashboard() {
  const [summary, setSummary] = useState<StatsSummary | null>(null)
  const [trend, setTrend] = useState<TrendPoint[]>([])
  const [cheatTypes, setCheatTypes] = useState<CheatTypeCount[]>([])
  const [err, setErr] = useState('')
  const [auto, setAuto] = useState(true)

  async function load() {
    try {
      const [s, t, c] = await Promise.all([
        api.stats.summary(),
        api.stats.trend(7),
        api.stats.cheatTypes(),
      ])
      setSummary(s)
      setTrend(t)
      setCheatTypes(c)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
    let timer: ReturnType<typeof setInterval> | undefined
    if (auto) timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [auto])

  const trendOption: EChartsOption = useMemo(
    () => ({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 16, top: 24, bottom: 28 },
      xAxis: {
        type: 'category',
        data: trend.map((p) => p.date.slice(5)),
        axisLine: { lineStyle: { color: '#8b949e' } },
        axisLabel: { color: '#8b949e' },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } },
        axisLabel: { color: '#8b949e' },
      },
      series: [
        {
          name: '红屏事件',
          type: 'line',
          smooth: true,
          symbolSize: 6,
          data: trend.map((p) => p.count),
          lineStyle: { color: '#ff3b30', width: 2.5 },
          itemStyle: { color: '#ff3b30' },
          areaStyle: { color: 'rgba(255,59,48,0.15)' },
        },
      ],
    }),
    [trend],
  )

  const pieOption: EChartsOption = useMemo(() => {
    const palette = ['#58a6ff', '#d29922', '#ff3b30', '#3fb950', '#a371f7', '#39c5cf']
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: {
        bottom: 0,
        textStyle: { color: '#8b949e' },
        type: 'scroll',
      },
      series: [
        {
          name: '作弊类型分布',
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '44%'],
          itemStyle: { borderRadius: 4, borderColor: '#161b22', borderWidth: 2 },
          label: { color: '#8b949e' },
          data: cheatTypes.map((c, i) => ({
            name: c.cheat_type,
            value: c.count,
            itemStyle: { color: palette[i % palette.length] },
          })),
        },
      ],
    }
  }, [cheatTypes])

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <h1 style={{ margin: 0, fontSize: 22 }}>数据大盘</h1>
        <label style={{ fontSize: 13, color: '#8b949e' }}>
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> 自动刷新
        </label>
        {err && <span style={{ color: '#ff3b30', fontSize: 13 }}>{err}</span>}
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        <Metric label="在线玩家 (PTEID)" value={summary?.online_pteid ?? '-'} accent="#3fb950" />
        <Metric label="今日检测事件" value={summary?.detections_today ?? '-'} accent="#58a6ff" />
        <Metric label="红屏事件(7天)" value={summary?.redscreen_count ?? '-'} accent="#ff3b30" />
        <Metric label="待查端" value={summary?.pending_inspect ?? '-'} accent="#d29922" />
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        <Metric label="进行中查端" value={summary?.active_inspect ?? '-'} />
        <Metric label="永久作弊记录" value={summary?.total_cheat_records ?? '-'} />
        <Metric label="注册账号" value={summary?.total_accounts ?? '-'} />
        <Metric label="基岩 / Java 玩家" value={`${summary?.bedrock_players ?? '-'} / ${summary?.java_players ?? '-'}`} />
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        <div className="card" style={{ flex: '1 1 420px', minWidth: 320 }}>
          <h3 style={{ marginTop: 0 }}>近 7 天红屏趋势</h3>
          <EChart option={trendOption} height={230} />
        </div>
        <div className="card" style={{ flex: '1 1 320px', minWidth: 280 }}>
          <h3 style={{ marginTop: 0 }}>作弊类型分布</h3>
          <EChart option={pieOption} height={230} />
        </div>
      </div>
    </div>
  )
}
