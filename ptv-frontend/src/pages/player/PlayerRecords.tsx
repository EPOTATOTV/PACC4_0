import { useEffect, useState } from 'react'
import { Alert, Card, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../../api/client'
import type { CheatRecord } from '../../types'

const { Title, Text } = Typography

export default function PlayerRecords() {
  const [records, setRecords] = useState<CheatRecord[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.player.records()
      .then(setRecords)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false))
  }, [])

  const riskColor = (v: number) => (v >= 80 ? 'error' : v >= 60 ? 'warning' : 'success')

  const columns: TableColumnsType<CheatRecord> = [
    { title: '类型', dataIndex: 'cheatType', render: (t: string, r) => <span>{t} <Text type="secondary">L{r.level}</Text></span> },
    { title: '风险', dataIndex: 'riskScore', width: 90, sorter: (a, b) => a.riskScore - b.riskScore, render: (v: number) => <Tag color={riskColor(v)}>{v}</Tag> },
    { title: '查端结论', dataIndex: 'inspectConclusion', render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: '状态', dataIndex: 'revoked', width: 100, render: (v: boolean) => (v ? <Tag color="default">已撤销</Tag> : <Tag color="error">有效</Tag>) },
    { title: '时间', dataIndex: 'occurredAt', width: 180, render: (v?: string) => <Text type="secondary" style={{ whiteSpace: 'nowrap' }}>{fmt(v)}</Text> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>我的作弊记录</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Card styles={{ body: { padding: 0 } }}>
        <Table<CheatRecord>
          rowKey="recordId"
          columns={columns}
          dataSource={records}
          loading={loading}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 640 }}
          locale={{ emptyText: '暂无记录' }}
        />
      </Card>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}