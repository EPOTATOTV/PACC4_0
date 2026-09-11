import { useEffect, useState } from 'react'
import { Alert, Badge, Card, Statistic, Table, Tag, Typography } from 'antd'
import { api } from '../api/client'
import type { CounterMeasureEnvOverview, DmaRiskEvent } from '../types'

const { Title, Text } = Typography

const levelColor: Record<string, string> = {
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'success',
} as const

function fmtTime(s: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function findingsText(e: DmaRiskEvent): string {
  const list = (e.findings || '').split(',').filter(Boolean)
  return list.length ? list.join(' · ') : '-'
}

export default function Countermeasure() {
  const [data, setData] = useState<CounterMeasureEnvOverview | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    let alive = true
    api.countermeasure
      .environment()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setErr((e as Error).message))
    return () => {
      alive = false
    }
  }, [])

  const highNames = (data?.high_risk_accounts ?? []).map((a) => a.pteid)
  const highEvents = (data?.recent ?? []).filter((e) => highNames.includes(e.pteid))

  const columns = [
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', render: fmtTime, width: 150 },
    { title: 'PTEID', dataIndex: 'pteid', key: 'pteid' },
    {
      title: '环境分',
      dataIndex: 'score',
      key: 'score',
      width: 90,
      sorter: (a: DmaRiskEvent, b: DmaRiskEvent) => a.score - b.score,
    },
    {
      title: '级别',
      dataIndex: 'level',
      key: 'level',
      width: 100,
      render: (l: string) => <Tag color={(levelColor[l] ?? 'default') as string}>{l}</Tag>,
    },
    {
      title: '命中项',
      key: 'findings',
      render: (_: unknown, r: DmaRiskEvent) => <span style={{ fontSize: 12 }}>{findingsText(r)}</span>,
    },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>DMA / IOMMU 环境巡检</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        玩家端上报 IOMMU / ACPI / PCIe 可疑设备 / 内核调试 / 反调试矩阵，服务端打分分级并固化。客户端采集，此处只做判定与审计。
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 18 }} closable />}

      <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: 20 }}>
        <Card size="small" style={{ minWidth: 180 }}>
          <Statistic title="高危账号" value={data?.high_risk_accounts.length ?? 0} valueStyle={{ color: '#ff3b30', fontWeight: 700 }} />
          {highNames.length > 0 && <Text type="secondary" style={{ fontSize: 11 }}>{highNames.join(', ')}</Text>}
        </Card>
        <Card size="small" style={{ minWidth: 180 }}>
          <Statistic title="近期上报事件" value={data?.recent.length ?? 0} />
        </Card>
        <Card size="small" style={{ minWidth: 220 }}>
          <Statistic title="分级阈值（深观察 / 高危）" value={`${data?.thresholds.environment_suspect ?? '-'} / ${data?.thresholds.environment_high ?? '-'}`} />
        </Card>
      </div>

      <div style={{ borderBottom: '1px solid var(--border-strong)', marginBottom: 16, paddingBottom: 6 }}>
        <span style={{ fontWeight: 600 }}>高危账号事件</span>
      </div>
      <Card
        size="small"
        style={{ border: '1px solid var(--border-strong)', boxShadow: 'none', marginBottom: 20 }}
        styles={{ body: { padding: 0 } }}
      >
        <Table
          rowKey="id"
          size="small"
          dataSource={highEvents}
          columns={columns}
          pagination={false}
          locale={{ emptyText: '暂无高危 DMA / 调试环境事件' }}
        />
      </Card>

      <div style={{ borderBottom: '1px solid var(--border-strong)', marginBottom: 16, paddingBottom: 6 }}>
        <span style={{ fontWeight: 600 }}>近期事件</span>
        <Badge style={{ marginLeft: 10 }} count={data?.recent.length ?? 0} />
      </div>
      <Card size="small" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none' }} styles={{ body: { padding: 0 } }}>
        <Table
          rowKey="id"
          size="small"
          dataSource={data?.recent ?? []}
          columns={columns}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </Card>
    </div>
  )
}