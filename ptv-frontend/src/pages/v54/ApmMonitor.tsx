import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Empty, Segmented, Select, Space, Table, Tag, message } from 'antd'
import type { TableColumnsType } from 'antd'
import type { EChartsOption } from 'echarts'
import { ReloadOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import EChart from '../../components/EChart'
import { api } from '../../api/client'
import type {
  V54ApmAlert, V54ApmMatrix, V54ApmOverview, V54ApmTrend, V54ApmVersionRegression,
} from '../../types'
import { useTableRowReveal } from '../../hooks/useGSAP'

const HOUR_OPTIONS = [
  { label: '近 1 小时', value: 1 },
  { label: '近 6 小时', value: 6 },
  { label: '近 24 小时', value: 24 },
  { label: '近 72 小时', value: 72 },
  { label: '近 7 天', value: 168 },
]

// 指标名与后端 APM 目录一一对应；默认勾选的是 PACC 自身开销四项。
const SYSTEM_METRICS = ['sys_cpu_process', 'sys_mem_process', 'sys_disk_read', 'sys_net_rx']
const GAME_METRICS = ['game_fps', 'game_frame_time_p95', 'game_input_latency_p95']
const DETECT_METRICS = ['detect_collect_latency_p95', 'detect_engine_latency', 'detect_ai_infer_latency']
const MATRIX_METRICS = ['sys_cpu_process', 'sys_mem_process', 'game_fps', 'detect_engine_latency']
const REGRESSION_METRICS = ['detect_engine_latency', 'detect_ai_infer_latency', 'game_frame_time_p95']

// 级别配色：P0 红、P1 橙、P2 默认
const LEVEL_COLOR: Record<string, string> = { P0: 'red', P1: 'orange', P2: 'default' }

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

/** 多指标趋势折线：同一坐标轴叠加多条 series，共用 axis tooltip。 */
function multiLineOption(trends: V54ApmTrend[], field: 'avg' | 'p95'): EChartsOption {
  const times = Array.from(new Set(trends.flatMap((t) => t.points.map((p) => p.t)))).sort()
  const at = new Map(times.map((t, i) => [t, i]))
  const unit = trends.find((t) => t.unit)?.unit ?? ''
  const series = trends.map((t) => {
    const data: (number | null)[] = times.map(() => null)
    t.points.forEach((p) => {
      const i = at.get(p.t)
      if (i !== undefined) data[i] = field === 'p95' ? p.p95 : p.avg
    })
    return {
      name: t.label || t.metric,
      type: 'line' as const,
      smooth: true,
      symbol: 'none',
      connectNulls: true,
      areaStyle: { opacity: 0.08 },
      data,
    }
  })
  return {
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => (typeof v === 'number' ? `${v}${unit}` : '-'),
    },
    legend: { data: series.map((s) => s.name), right: 0, top: 0, textStyle: { fontSize: 11 } },
    grid: { left: 6, right: 6, top: 36, bottom: 2, containLabel: true },
    xAxis: {
      type: 'category',
      data: times.map((t) => t.slice(5, 16).replace('T', ' ')),
      axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
    },
    yAxis: {
      type: 'value',
      name: unit,
      splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } },
    },
    series,
  }
}

export default function ApmMonitor() {
  const [hours, setHours] = useState(24)
  const [platform, setPlatform] = useState('')
  const [clientVer, setClientVer] = useState('')

  const [overview, setOverview] = useState<V54ApmOverview | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  const [selectedMetrics, setSelectedMetrics] = useState<string[]>(SYSTEM_METRICS)
  const [trends, setTrends] = useState<Record<string, V54ApmTrend>>({})

  const [matrix, setMatrix] = useState<V54ApmMatrix | null>(null)

  const [regMetric, setRegMetric] = useState('detect_engine_latency')
  const [regression, setRegression] = useState<V54ApmVersionRegression | null>(null)

  const [alertStatus, setAlertStatus] = useState('OPEN')
  const [alerts, setAlerts] = useState<V54ApmAlert[]>([])
  const [openTotal, setOpenTotal] = useState(0)
  const [busy, setBusy] = useState('')

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })

  // 指标多选的稳定派生值：避免数组引用变化反复触发趋势请求
  const metricKey = useMemo(() => selectedMetrics.join(','), [selectedMetrics])

  const loadOverview = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.v54.apm.overview(hours, platform || undefined, clientVer || undefined)
      setOverview(d)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [hours, platform, clientVer])

  const loadTrends = useCallback(async () => {
    const metrics = Array.from(new Set([
      ...(metricKey ? metricKey.split(',') : []),
      ...GAME_METRICS,
      ...DETECT_METRICS,
    ]))
    if (metrics.length === 0) return
    try {
      const list = await Promise.all(
        metrics.map((m) => api.v54.apm.trend(m, hours, platform || undefined, clientVer || undefined)),
      )
      const next: Record<string, V54ApmTrend> = {}
      list.forEach((t) => { next[t.metric] = t })
      setTrends(next)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [metricKey, hours, platform, clientVer])

  const loadMatrix = useCallback(async () => {
    try {
      setMatrix(await api.v54.apm.matrix(MATRIX_METRICS, hours))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [hours])

  const loadRegression = useCallback(async () => {
    try {
      setRegression(await api.v54.apm.versionRegression(regMetric, platform || undefined))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [regMetric, platform])

  const loadAlerts = useCallback(async () => {
    try {
      const d = await api.v54.apm.alerts(alertStatus === 'ALL' ? undefined : alertStatus, 50)
      setAlerts(d.items ?? [])
      setOpenTotal(d.open_total ?? 0)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [alertStatus])

  useEffect(() => { void loadOverview() }, [loadOverview])
  useEffect(() => { void loadTrends() }, [loadTrends])
  useEffect(() => { void loadMatrix() }, [loadMatrix])
  useEffect(() => { void loadRegression() }, [loadRegression])
  useEffect(() => { void loadAlerts() }, [loadAlerts])

  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [alerts, reveal])

  function refreshAll() {
    void loadOverview()
    void loadTrends()
    void loadMatrix()
    void loadRegression()
    void loadAlerts()
  }

  async function ack(row: V54ApmAlert) {
    setBusy(row.id)
    try {
      await api.v54.apm.ackAlert(row.id)
      message.success(`告警 ${row.alert_name} 已确认`)
      await loadAlerts()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy('')
    }
  }

  const systemTrends = useMemo(
    () => selectedMetrics.map((m) => trends[m]).filter((t): t is V54ApmTrend => !!t && t.points.length > 0),
    [selectedMetrics, trends],
  )
  const gameTrends = useMemo(
    () => GAME_METRICS.map((m) => trends[m]).filter((t): t is V54ApmTrend => !!t && t.points.length > 0),
    [trends],
  )
  const detectTrends = useMemo(
    () => DETECT_METRICS.map((m) => trends[m]).filter((t): t is V54ApmTrend => !!t && t.points.length > 0),
    [trends],
  )

  const systemOption = useMemo(() => multiLineOption(systemTrends, 'avg'), [systemTrends])
  const gameOption = useMemo(() => multiLineOption(gameTrends, 'p95'), [gameTrends])
  const detectOption = useMemo(() => multiLineOption(detectTrends, 'p95'), [detectTrends])

  // 平台 × 指标 P95 热力图：visualMap 上限取观测最大值，颜色仅在行内可比
  const matrixOption = useMemo<EChartsOption>(() => {
    const platforms = matrix?.platforms ?? []
    const metrics = matrix?.metrics ?? []
    const cells = matrix?.cells ?? []
    const xAt = new Map(platforms.map((p, i) => [p, i]))
    const yAt = new Map(metrics.map((m, i) => [m.name, i]))
    const data = cells
      .filter((c) => xAt.has(c.platform) && yAt.has(c.metric))
      .map((c) => [xAt.get(c.platform), yAt.get(c.metric), c.p95])
    const max = data.reduce((m, c) => Math.max(m, c[2] as number), 0) || 1
    return {
      tooltip: {
        position: 'top',
        formatter: (params: unknown) => {
          const p = params as { value: [number, number, number] }
          const [x, y, v] = p.value
          const mt = metrics[y]
          return `${platforms[x]} / ${mt?.label ?? '-'} / P95 ${v}${mt?.unit ?? ''}`
        },
      },
      grid: { left: 6, right: 6, top: 12, bottom: 44, containLabel: true },
      xAxis: {
        type: 'category',
        data: platforms,
        splitArea: { show: true },
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      yAxis: {
        type: 'category',
        data: metrics.map((m) => m.label),
        splitArea: { show: true },
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      visualMap: {
        min: 0,
        max,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
        textStyle: { fontSize: 11, color: 'var(--muted)' },
        inRange: { color: ['rgba(64,150,255,.25)', 'rgba(255,163,64,.72)', 'rgba(255,77,61,.9)'] },
      },
      series: [{
        type: 'heatmap',
        data,
        label: { show: true, fontSize: 11 },
        emphasis: { itemStyle: { shadowBlur: 6, shadowColor: 'rgba(0,0,0,.45)' } },
      }],
    }
  }, [matrix])

  // 版本回归柱状图：回归版本标红，柱顶标注相对基线的变化百分比
  const regressionOption = useMemo<EChartsOption>(() => {
    const rows = regression?.versions ?? []
    const unit = regression?.unit ?? ''
    const delta = (params: unknown) => {
      const i = (params as { dataIndex: number }).dataIndex
      const r = rows[i]
      if (!r) return ''
      if (r.baseline) return `${r.p95}${unit}（基线）`
      return `${r.p95}${unit}\n${r.delta_percent > 0 ? '+' : ''}${r.delta_percent}%`
    }
    return {
      tooltip: { trigger: 'axis', valueFormatter: (v) => (typeof v === 'number' ? `${v}${unit}` : '-') },
      grid: { left: 6, right: 6, top: 26, bottom: 2, containLabel: true },
      xAxis: {
        type: 'category',
        data: rows.map((r) => r.client_ver),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      yAxis: {
        type: 'value',
        name: unit,
        splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } },
      },
      series: [{
        name: 'P95',
        type: 'bar',
        barWidth: 26,
        label: { show: true, position: 'top', fontSize: 11, formatter: delta },
        data: rows.map((r) => ({
          value: r.p95,
          itemStyle: { color: r.regressed ? '#ff4d3d' : '#4096ff' },
        })),
      }],
    }
  }, [regression])

  const columns: TableColumnsType<V54ApmAlert> = [
    { title: '时间', dataIndex: 'occurred_at', width: 170, render: (v: string) => fmt(v) },
    { title: '告警名', dataIndex: 'alert_name', width: 160 },
    { title: '指标', dataIndex: 'metric_name', width: 190, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '平台', dataIndex: 'platform', width: 84 },
    { title: '版本', dataIndex: 'client_ver', width: 90 },
    {
      title: '级别', dataIndex: 'level', width: 74,
      render: (v: string) => <Tag color={LEVEL_COLOR[v] ?? 'default'}>{v}</Tag>,
    },
    { title: '阈值', dataIndex: 'threshold', width: 88 },
    {
      title: '实测值', dataIndex: 'observed', width: 92,
      render: (v: number, r) => <span style={{ color: v > r.threshold ? '#ff4d3d' : undefined }}>{v}</span>,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => (v === 'ACKED' ? <Tag color="green">已确认</Tag> : <Tag color="orange">未确认</Tag>),
    },
    {
      title: '处理', width: 96, fixed: 'right',
      render: (_, r) => (
        <Button
          size="small"
          type="text"
          disabled={r.status === 'ACKED'}
          loading={busy === r.id}
          onClick={() => void ack(r)}
        >
          确认
        </Button>
      ),
    },
  ]

  const catalogOptions = (overview?.catalog ?? [])
    .filter((c) => c.group === 'system')
    .map((c) => ({ label: `${c.label}（${c.unit}）`, value: c.name }))

  return (
    <div>
      <PageHeader
        title="APM 性能监控"
        description="PACC 客户端自身资源开销、游戏性能影响与检测链路耗时的分平台 / 版本聚合"
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <Space wrap>
            <Select
              value={platform}
              onChange={setPlatform}
              style={{ width: 132 }}
              options={[
                { label: '全部平台', value: '' },
                ...(overview?.available_platforms ?? []).map((p) => ({ label: p, value: p })),
              ]}
            />
            <Select
              value={clientVer}
              onChange={setClientVer}
              style={{ width: 132 }}
              options={[
                { label: '全部版本', value: '' },
                ...(overview?.available_versions ?? []).map((v) => ({ label: v, value: v })),
              ]}
            />
            <Select
              value={hours}
              onChange={setHours}
              style={{ width: 128 }}
              options={HOUR_OPTIONS}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>
          </Space>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="在线客户端" value={overview?.cards.online_clients ?? 0} hint={`覆盖 ${overview?.cards.client_versions ?? 0} 个客户端版本`} />
        <MetricCard label="平均 CPU" value={`${(overview?.cards.avg_cpu_percent ?? 0).toFixed(2)} %`} />
        <MetricCard label="平均内存" value={`${(overview?.cards.avg_mem_mb ?? 0).toFixed(1)} MB`} />
        <MetricCard label="FPS 影响" value={`${(overview?.cards.avg_fps_impact_percent ?? 0).toFixed(2)} %`} accent="#ffa940" hint="客户端注入导致的帧率损失" />
        <MetricCard label="误报率" value={`${((overview?.cards.false_positive_rate ?? 0) * 100).toFixed(2)} %`} accent="#ff4d3d" />
        <MetricCard label="采样点数" value={overview?.cards.sample_count ?? 0} />
      </div>

      <Card
        title="系统资源趋势"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={
          <Space size={8}>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>指标</span>
            <Select
              mode="multiple"
              size="small"
              value={selectedMetrics}
              onChange={setSelectedMetrics}
              style={{ minWidth: 300 }}
              maxTagCount="responsive"
              placeholder="选择要绘制的系统指标"
              options={catalogOptions}
            />
          </Space>
        }
      >
        {systemTrends.length > 0
          ? <EChart option={systemOption} height={236} />
          : <Empty description="所选指标在该窗口内没有采样" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8, lineHeight: 1.7 }}>
          取窗口内逐小时平均值；不同指标单位不同（% / MB / KB/s），共用一条数值轴，只用于看各自走势，不做量纲归一。
        </div>
      </Card>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 15, marginBottom: 15 }}>
        <Card title="游戏性能趋势" className="pacc-glass-md">
          {gameTrends.length > 0
            ? <EChart option={gameOption} height={228} />
            : <Empty description="该窗口内没有游戏性能采样" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8 }}>逐小时 P95：帧率、帧时间、输入延迟。</div>
        </Card>
        <Card title="检测引擎性能趋势" className="pacc-glass-md">
          {detectTrends.length > 0
            ? <EChart option={detectOption} height={228} />
            : <Empty description="该窗口内没有检测链路采样" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8 }}>逐小时 P95：采集、引擎、AI 推理三段耗时。</div>
        </Card>
      </div>

      <Card title="平台性能对比矩阵" className="pacc-glass-md" style={{ marginBottom: 15 }}>
        {matrix && matrix.cells.length > 0
          ? <EChart option={matrixOption} height={288} />
          : <Empty description="该窗口内没有可对比的平台数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8, lineHeight: 1.7 }}>
          单元格为各平台各指标的 P95 原值，悬停可见平台 / 指标 / P95。颜色按观测最大值归一，且各指标单位不同，
          因此颜色只在同一指标行内可比，跨行深浅不代表性能优劣。
        </div>
      </Card>

      <Card
        title="版本性能回归对比"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={
          <Space size={8}>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>指标</span>
            <Select
              size="small"
              value={regMetric}
              onChange={setRegMetric}
              style={{ width: 220 }}
              options={REGRESSION_METRICS.map((m) => ({ label: m, value: m }))}
            />
          </Space>
        }
      >
        {regression && regression.versions.length > 0
          ? <EChart option={regressionOption} height={264} />
          : <Empty description="该指标没有多版本对比数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8, lineHeight: 1.7 }}>
          基线取最早版本；相对基线升高超过 {regression?.regression_threshold_percent ?? 20}% 判为回归（红色柱）。
        </div>
      </Card>

      <Card styles={{ body: { padding: 0 } }} className="pacc-glass-md">
        <div style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '12px 14px 10px', flexWrap: 'wrap' }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>性能异常告警</span>
          <Segmented
            size="small"
            value={alertStatus}
            onChange={(v) => setAlertStatus(v as string)}
            options={[
              { label: '未确认', value: 'OPEN' },
              { label: '已确认', value: 'ACKED' },
              { label: '全部', value: 'ALL' },
            ]}
          />
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>未确认合计 {openTotal} 条 · 确认仅表示已读，不代表问题已修复</span>
        </div>
        <div ref={tableRef}>
          <Table<V54ApmAlert>
            rowKey="id"
            columns={columns}
            dataSource={alerts}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            scroll={{ x: 1180 }}
            locale={{ emptyText: '暂无符合条件的性能告警' }}
          />
        </div>
      </Card>
    </div>
  )
}