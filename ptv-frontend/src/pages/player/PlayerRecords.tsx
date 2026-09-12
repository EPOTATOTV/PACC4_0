import { useEffect, useMemo, useState } from 'react'
import { Alert, Card, Col, Input, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type { CheatRecord } from '../../types'
import EChart from '../../components/EChart'
import type { EChartsOption } from 'echarts'
import MetricCard from '../../components/MetricCard'

const { Title, Text } = Typography

export default function PlayerRecords() {
  const [records, setRecords] = useState<CheatRecord[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const [typeFilter, setTypeFilter] = useState<string>()
  const [minRisk, setMinRisk] = useState<number>()
  const [keyword, setKeyword] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    api.player.records()
      .then(setRecords)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false))
  }, [])

  const riskColor = (v: number) => (v >= 80 ? 'error' : v >= 60 ? 'warning' : 'success')

  const types = useMemo(() => Array.from(new Set(records.map((r) => r.cheatType))), [records])

  const filtered = useMemo(() => {
    return records.filter((r) => {
      if (typeFilter && r.cheatType !== typeFilter) return false
      if (minRisk && r.riskScore < minRisk) return false
      if (keyword.trim() && !r.cheatType.toLowerCase().includes(keyword.trim().toLowerCase())) return false
      return true
    })
  }, [records, typeFilter, minRisk, keyword])

  const riskDistOption: EChartsOption = useMemo(() => {
    const classified = records.reduce(
      (acc, r) => {
        if (r.riskScore >= 80) acc.high++
        else if (r.riskScore >= 60) acc.medium++
        else acc.low++
        return acc
      },
      { high: 0, medium: 0, low: 0 },
    )
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, textStyle: { color: '#8b949e' } },
      series: [
        {
          name: '风险分布',
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '44%'],
          itemStyle: { borderRadius: 4, borderColor: '#101319', borderWidth: 2 },
          label: { color: '#8b949e' },
          data: [
            { name: '高风险 ≥80', value: classified.high, itemStyle: { color: '#ff3b30' } },
            { name: '中风险 60-79', value: classified.medium, itemStyle: { color: '#d29922' } },
            { name: '低风险 <60', value: classified.low, itemStyle: { color: '#3fb950' } },
          ],
        },
      ],
    }
  }, [records])

  const columns: TableColumnsType<CheatRecord> = [
    {
      title: '类型',
      dataIndex: 'cheatType',
      render: (t: string, r) => (
        <span>
          {t} <Text type="secondary">L{r.level}</Text>
        </span>
      ),
    },
    {
      title: '风险',
      dataIndex: 'riskScore',
      width: 90,
      sorter: (a, b) => a.riskScore - b.riskScore,
      render: (v: number) => <Tag color={riskColor(v)}>{v}</Tag>,
    },
    { title: '查端结论', dataIndex: 'inspectConclusion', render: (v?: string) => v || <Text type="secondary">-</Text> },
    {
      title: '状态',
      dataIndex: 'revoked',
      width: 100,
      render: (v: boolean) => (v ? <Tag color="default">已撤销</Tag> : <Tag color="error">有效</Tag>),
    },
    { title: '时间', dataIndex: 'occurredAt', width: 190, render: (v?: string) => <Text type="secondary" style={{ whiteSpace: 'nowrap' }}>{fmt(v)}</Text> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>我的作弊记录</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Row gutter={[14, 14]} style={{ marginBottom: 14 }}>
        <Col xs={12} sm={6}><Metric label="记录总数" value={records.length} /></Col>
        <Col xs={12} sm={6}><Metric label="有效记录" value={records.filter((r) => !r.revoked).length} color="#ff3b30" /></Col>
        <Col xs={12} sm={6}><Metric label="已撤销" value={records.filter((r) => r.revoked).length} color="#3fb950" /></Col>
        <Col xs={12} sm={6}><Metric label="平均风险" value={records.length ? Math.round((records.reduce((a, r) => a + r.riskScore, 0) / records.length) * 10) / 10 : 0} /></Col>
      </Row>

      <Card
        title="风险分布"
        size="small"
        style={{ marginBottom: 14 }}
        extra={
          <Space wrap>
            <Select
              allowClear
              placeholder="检测类型"
              style={{ width: 150 }}
              value={typeFilter}
              onChange={(v) => setTypeFilter(v)}
              options={types.map((t) => ({ value: t, label: t }))}
            />
            <Select
              allowClear
              placeholder="最低风险"
              style={{ width: 120 }}
              value={minRisk}
              onChange={(v) => setMinRisk(v)}
              options={[60, 70, 80, 90].map((v) => ({ value: v, label: `≥ ${v}` }))}
            />
            <Input.Search allowClear placeholder="搜索类型" style={{ width: 160 }} onSearch={(v) => setKeyword(v)} />
          </Space>
        }
      >
        <EChart option={riskDistOption} height={180} />
      </Card>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<CheatRecord>
          rowKey="recordId"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 640 }}
          locale={{ emptyText: '暂无记录' }}
          onRow={(r) => ({
            onClick: () => r.alertId && navigate(`/portal/redscreen/${r.alertId}`),
            style: r.alertId ? { cursor: 'pointer' } : undefined,
          })}
        />
      </Card>
    </div>
  )
}

function Metric({ label, value, color }: { label: string; value: number; color?: string }) {
  const accent =
    color === '#ff3b30' ? 'var(--kpi-red)'
    : color === '#3fb950' ? 'var(--kpi-green)'
    : 'var(--kpi-blue)'
  return <MetricCard label={label} value={value} accent={accent} />
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}