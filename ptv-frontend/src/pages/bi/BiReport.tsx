import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Col, Empty, Row, Select, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { EChartsOption } from 'echarts'
import { api } from '../../api/client'
import type { BiCheatTypeRow, BiOverview } from '../../types'
import EChart from '../../components/EChart'
import MetricCard from '../../components/MetricCard'

const { Title, Text } = Typography

const DAY_OPTIONS = [7, 14, 30, 90]
const PALETTE = ['#58a6ff', '#d29922', '#ff3b30', '#3fb950', '#a371f7', '#39c5cf', '#f778ba', '#7a828e']

function trendOption(days: string[], red: number[], blue: number[] | null, redName: string, blueName?: string): EChartsOption {
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: '#8b949e' } },
    grid: { left: 40, right: 16, top: 34, bottom: 28 },
    xAxis: {
      type: 'category',
      data: days.map((d) => d.slice(5)),
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
      ...(blue
        ? [{
            name: blueName ?? '对比',
            type: 'line' as const,
            smooth: true,
            symbolSize: 5,
            data: blue,
            lineStyle: { color: '#58a6ff', width: 2 },
            itemStyle: { color: '#58a6ff' },
          }]
        : []),
      {
        name: redName,
        type: 'line' as const,
        smooth: true,
        symbolSize: 5,
        data: red,
        lineStyle: { color: '#ff3b30', width: 2.5 },
        itemStyle: { color: '#ff3b30' },
        areaStyle: { color: 'rgba(255,59,48,0.12)' },
      },
    ],
  }
}

export default function BiReport() {
  const [days, setDays] = useState(30)
  const [data, setData] = useState<BiOverview | null>(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)

  function load() {
    setLoading(true)
    api.bi
      .overview(days)
      .then((d) => {
        setData(d)
        setErr('')
      })
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false))
  }

  useEffect(load, [days])

  const cheatTypeColumns: ColumnsType<BiCheatTypeRow> = [
    { title: '作弊类型', dataIndex: 'cheat_type', key: 'cheat_type' },
    { title: '数量', dataIndex: 'count', key: 'count', width: 100 },
  ]

  const cheatTypePie: EChartsOption | null = useMemo(() => {
    if (!data) return null
    const items = data.cheat_types.items
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, type: 'scroll', textStyle: { color: '#8b949e' } },
      series: [
        {
          name: '作弊类型',
          type: 'pie',
          radius: ['42%', '66%'],
          center: ['50%', '44%'],
          itemStyle: { borderRadius: 4, borderColor: '#101319', borderWidth: 2 },
          label: { color: '#8b949e' },
          data: items.map((c, i) => ({
            name: c.cheat_type,
            value: c.count,
            itemStyle: { color: PALETTE[i % PALETTE.length] },
          })),
        },
      ],
    }
  }, [data])

  const reputationOption: EChartsOption | null = useMemo(() => {
    if (!data) return null
    const rep = data.player_profile.reputation
    const order = ['0_49', '50_69', '70_84', '85_100']
    const labels = ['0-49 低', '50-69 中', '70-84 良', '85-100 优']
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 8, top: 20, bottom: 28 },
      xAxis: { type: 'category', data: labels, axisLabel: { color: '#8b949e' } },
      yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } }, axisLabel: { color: '#8b949e' } },
      series: [
        {
          name: '账号数',
          type: 'bar',
          barWidth: '48%',
          data: order.map((k) => ({ value: rep[k] ?? 0, itemStyle: { borderRadius: [4, 4, 0, 0], color: PALETTE[0] } })),
          label: { show: true, position: 'top', color: '#8b949e' },
        },
      ],
    }
  }, [data])

  function downloadCsv(rows: Record<string, unknown>[], name: string) {
    if (!rows.length) return
    const headers = Object.keys(rows[0])
    const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`
    const csv = [headers.join(','), ...rows.map((r) => headers.map((h) => esc(r[h])).join(','))].join('\n')
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${name}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  if (!data && !loading) {
    return <div style={{ padding: 32 }}><Empty description="暂无数据" /></div>
  }

  const fpRate = data?.redscreen_health.false_positive_rate ?? 0
  const tamperColumns: Items = [
    { label: '检测趋势', category: 'detection', val: data?.detection_trend.total ?? 0, accent: 'var(--kpi-blue)' },
    { label: '红屏事件', category: 'redscreen', val: data?.redscreen_trend.total ?? 0, accent: 'var(--kpi-red)' },
    { label: '误报率', category: 'fp', val: fpRate, accent: 'var(--kpi-amber)', pct: true },
    { label: '待查红屏', category: 'pending', val: data?.redscreen_health.pending ?? 0, accent: 'var(--kpi-green)' },
    { label: '申诉总数', category: 'appeal', val: data?.appeal_funnel.total ?? 0, accent: 'var(--kpi-blue)' },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>数据平台 & BI 报表</Title>
        <Select<number>
          value={days}
          style={{ width: 110 }}
          options={DAY_OPTIONS.map((d) => ({ value: d, label: `近 ${d} 天` }))}
          onChange={setDays}
        />
        <Button
          size="small"
          onClick={() =>
            fullCsv(data, days)
          }
        >
          导出全部 CSV
        </Button>
        {err && <Alert type="error" showIcon message={err} closable onClose={() => setErr('')} style={{ flex: 1, minWidth: 200 }} />}
      </div>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        {tamperColumns.map((c) => (
          <Col xs={12} sm={12} md={8} lg={4} key={c.category}>
            <MetricCard label={c.label} value={c.pct ? Number(c.val.toFixed(2)) : c.val} accent={c.accent} hint={c.pct ? '%' : undefined} />
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={12}>
          <Card title="检测事件趋势" size="small">
            {data && (
              <EChart
                option={trendOption(data.detection_trend.days, data.detection_trend.counts, null, '检测事件')}
                height={230}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="红屏事件趋势" size="small">
            {data && (
              <EChart
                option={trendOption(data.redscreen_trend.days, data.redscreen_trend.counts, null, '红屏事件')}
                height={230}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="作弊类型分布" size="small">
            {cheatTypePie && <EChart option={cheatTypePie} height={230} />}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Table<BiCheatTypeRow>
            size="small"
            rowKey="cheat_type"
            pagination={false}
            dataSource={data?.cheat_types.items ?? []}
            columns={cheatTypeColumns}
            scroll={{ y: 190 }}
            title={() => (
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text strong>作弊类型明细</Text>
                <Button
                  size="small"
                  onClick={() => downloadCsv((data?.cheat_types.items ?? []).map((r) => ({ ...r })), `cheat-types-${days}d`)}
                >
                  导出 CSV
                </Button>
              </div>
            )}
          />
        </Col>
        <Col xs={24} lg={10}>
          <Card title="玩家信誉分布" size="small">
            {reputationOption && <EChart option={reputationOption} height={230} />}
          </Card>
        </Col>
        <Col xs={24} lg={6}>
          <Card title="版本分布" size="small">
            {data && <EditionSplit bedrock={data.edition_split.bedrock} java={data.edition_split.java} />}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title="红屏健康" size="small">
            {data && <HealthDetail h={data.redscreen_health} c={data.cheat_record_health} />}
          </Card>
        </Col>
      </Row>
    </div>
  )
}

type Items = { label: string; category: string; val: number; accent: string; pct?: boolean }[]

function EditionSplit({ bedrock, java }: { bedrock: number; java: number }) {
  const total = bedrock + java || 1
  const data: EChartsOption = {
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '48%'],
        label: { color: '#8b949e' },
        data: [
          { name: `基岩版 ${bedrock}`, value: bedrock, itemStyle: { color: '#3fb950' } },
          { name: `Java 版 ${java}`, value: java, itemStyle: { color: '#58a6ff' } },
        ],
      },
    ],
  }
  return (
    <div>
      <EChart option={data} height={170} />
      <div style={{ textAlign: 'center', color: '#8b949e', fontSize: 12 }}>
        基岩 {Math.round((bedrock / total) * 100)}% · Java {Math.round((java / total) * 100)}%
      </div>
    </div>
  )
}

function HealthDetail({ h, c }: { h: BiOverview['redscreen_health']; c: BiOverview['cheat_record_health'] }) {
  const rows = [
    { label: '已确认作弊', v: h.confirmed },
    { label: '待查验', v: h.pending },
    { label: '误报（红屏）', v: h.false_positive },
    { label: '作弊记录（未撤销）', v: c.persisted },
    { label: '已撤销（误报）', v: c.revoked },
  ]
  return (
    <div>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <td style={{ padding: '6px 0', color: '#8b949e' }}>{r.label}</td>
              <td style={{ padding: '6px 0', textAlign: 'right', fontWeight: 600 }}>{r.v}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Text type="secondary" style={{ fontSize: 12 }}>
        红屏误报率 {h.false_positive_rate}%（{h.false_positive}/{h.total}）
      </Text>
    </div>
  )
}

function fullCsv(data: BiOverview | null, days: number) {
  if (!data) return
  const rows: Record<string, unknown>[] = data.detection_trend.days.map((d, i) => ({
    日期: d,
    检测事件: data.detection_trend.counts[i],
    红屏事件: data.redscreen_trend.counts[i],
    登录成功: data.login_audit.success[i],
    登录失败: data.login_audit.fail[i],
  }))
  const blob = new Blob(['\ufeff' + ['日期,检测事件,红屏事件,登录成功,登录失败']
    .concat(rows.map((r) => `${r['日期']},${r['检测事件']},${r['红屏事件']},${r['登录成功']},${r['登录失败']}`))
    .join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `bi-overview-${days}d.csv`
  a.click()
  URL.revokeObjectURL(url)
}