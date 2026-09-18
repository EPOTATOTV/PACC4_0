import { useEffect, useMemo, useState } from 'react'
import { Checkbox, Col, Row } from 'antd'
import type { EChartsOption } from 'echarts'
import { api } from '../api/client'
import type { CheatTypeCount, StatsSummary, TrendPoint } from '../types'
import EChart from '../components/EChart'
import MetricCard from '../components/MetricCard'
import PageHeader from '../components/PageHeader'
import { gsap, motionAllowed, motionDuration } from '../gsap'
import { useGSAP } from '../hooks/useGSAP'

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
      setErr('')
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
          itemStyle: { borderRadius: 4, borderColor: '#101319', borderWidth: 2 },
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

  // §4.1.2 入场序列：标题先落位，KPI 带弹性依次抬起，图表区随后跟进。
  // 三段之间存在轻微时间重叠（负偏移），避免“一段播完再播下一段”的机械感。
  const rootRef = useGSAP<HTMLDivElement>(({ el }) => {
    if (!motionAllowed()) return
    const header = el.querySelector('[data-motion="header"]')
    const kpi = el.querySelectorAll('[data-motion="kpi"]')
    const charts = el.querySelectorAll('[data-motion="chart"]')

    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })
    if (header) {
      tl.from(header, { y: -12, opacity: 0, duration: motionDuration(0.36) })
    }
    if (kpi.length > 0) {
      tl.from(
        kpi,
        {
          y: 22,
          opacity: 0,
          duration: motionDuration(0.48),
          stagger: motionDuration(0.055),
          ease: 'back.out(1.5)',
          clearProps: 'all',
        },
        '-=0.16',
      )
    }
    if (charts.length > 0) {
      tl.from(
        charts,
        { y: 26, opacity: 0, duration: motionDuration(0.5), stagger: motionDuration(0.09), clearProps: 'all' },
        '-=0.28',
      )
    }
  }, [])

  return (
    <div ref={rootRef}>
      <div data-motion="header">
        <PageHeader
          title="数据大盘"
          extra={<Checkbox checked={auto} onChange={(e) => setAuto(e.target.checked)}>自动刷新</Checkbox>}
          error={err || undefined}
          onCloseError={() => setErr('')}
        />
      </div>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter label="在线玩家 (PTEID)" value={summary?.online_pteid ?? '-'} accent="var(--kpi-green)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.06} label="今日检测事件" value={summary?.detections_today ?? '-'} accent="var(--kpi-blue)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.12} label="红屏事件(7天)" value={summary?.redscreen_count ?? '-'} accent="var(--kpi-red)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.18} label="待查端" value={summary?.pending_inspect ?? '-'} accent="var(--kpi-amber)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.24} label="进行中查端" value={summary?.active_inspect ?? '-'} accent="var(--kpi-muted)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.3} label="永久作弊记录" value={summary?.total_cheat_records ?? '-'} accent="var(--kpi-red)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard counter counterDelay={0.36} label="注册账号" value={summary?.total_accounts ?? '-'} accent="var(--kpi-blue)" /></Col>
        <Col xs={12} sm={12} md={6} data-motion="kpi"><MetricCard label="基岩 / Java 玩家" value={`${summary?.bedrock_players ?? '-'} / ${summary?.java_players ?? '-'}`} accent="var(--kpi-green)" /></Col>
      </Row>

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={15} data-motion="chart">
          <div style={{ border: '1px solid var(--border)', borderRadius: 8, background: 'var(--panel)', padding: '14px 16px 8px' }}>
            <div className="section-title" style={{ margin: '0 0 8px' }}>近 7 天红屏趋势</div>
            <EChart option={trendOption} height={230} />
          </div>
        </Col>
        <Col xs={24} lg={9} data-motion="chart">
          <div style={{ border: '1px solid var(--border)', borderRadius: 8, background: 'var(--panel)', padding: '14px 16px 8px' }}>
            <div className="section-title" style={{ margin: '0 0 8px' }}>作弊类型分布</div>
            <EChart option={pieOption} height={230} />
          </div>
        </Col>
      </Row>
    </div>
  )
}