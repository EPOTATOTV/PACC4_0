import { useCallback, useEffect, useState } from 'react'
import { Alert, Card, Col, Progress, Row, Statistic, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../../api/client'
import type { CheatTypeDistRow, EffectivenessSummary } from '../../types'
import EChart from '../../components/EChart'
import type { EChartsOption } from 'echarts'

function MetricCard({ title, value, suffix, tone }: { title: string; value: number | string; suffix?: string; tone?: 'green' | 'red' }) {
  return (
    <Card size="small">
      <Statistic
        title={title}
        value={value}
        suffix={suffix}
        valueStyle={{ color: tone === 'green' ? '#3fb68b' : tone === 'red' ? '#ff4d4f' : undefined, fontWeight: 600 }}
      />
    </Card>
  )
}

export default function Effectiveness() {
  const [sum, setSum] = useState<EffectivenessSummary | null>(null)
  const [distribution, setDistribution] = useState<CheatTypeDistRow[]>([])
  const [err, setErr] = useState('')

  const load = useCallback(async () => {
    try {
      const [s, d] = await Promise.all([api.p1.effectiveness(), api.p1.cheatTypeDistribution()])
      setSum(s)
      setDistribution(d.cheat_types ?? [])
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const pieOption: EChartsOption = {
    tooltip: { trigger: 'item' },
    legend: { textStyle: { color: '#98a0ae' }, top: 0 },
    series: [
      {
        type: 'pie',
        radius: ['42%', '70%'],
        center: ['50%', '55%'],
        itemStyle: { borderColor: '#101319', borderWidth: 2 },
        label: { color: '#c7ccd6' },
        data: distribution.map((r) => ({ name: r.cheat_type, value: r.count })),
      },
    ],
  }

  const columns: TableColumnsType<CheatTypeDistRow> = [
    { title: '外挂类型', dataIndex: 'cheat_type' },
    { title: '数量', dataIndex: 'count', width: 100 },
    {
      title: '占比', width: 220,
      render: (_, r) => {
        const total = distribution.reduce((a, b) => a + b.count, 0)
        const pct = total <= 0 ? 0 : Math.round((r.count / total) * 100)
        return <Progress percent={pct} size="small" />
      },
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>反作弊效果分析</h3>
        {sum && (
          <Tag color={sum.healthy ? 'green' : 'red'}>
            系统{sum.healthy ? '健康' : '需关注'}（误报率 {sum.false_positive_pct}%）
          </Tag>
        )}
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={12} md={6}><MetricCard title="作弊记录总数" value={sum?.total_cheat_records ?? 0} /></Col>
        <Col xs={12} md={6}><MetricCard title="已确认作弊" value={sum?.confirmed_cheats ?? 0} tone="red" /></Col>
        <Col xs={12} md={6}><MetricCard title="误报撤销" value={sum?.false_positives ?? 0} /></Col>
        <Col xs={12} md={6}><MetricCard title="命中精度" value={sum?.precision_pct ?? 0} suffix="%" tone="green" /></Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={14}>
          <Card title="外挂类型分布" size="small">
            {distribution.length > 0
              ? <Table<CheatTypeDistRow> rowKey="cheat_type" columns={columns} dataSource={distribution} pagination={false} />
              : <div style={{ color: 'var(--muted)', padding: 24, textAlign: 'center' }}>暂无分布数据</div>}
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="占比视图" size="small">
            {distribution.length > 0
              ? <EChart option={pieOption} height={300} />
              : <div style={{ color: 'var(--muted)', padding: 24, textAlign: 'center' }}>暂无数据</div>}
          </Card>
        </Col>
      </Row>
    </div>
  )
}