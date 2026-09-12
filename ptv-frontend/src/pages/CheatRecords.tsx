import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Popconfirm, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { CheatRecord } from '../types'

const { Title, Text } = Typography

export default function CheatRecords() {
  const [records, setRecords] = useState<CheatRecord[]>([])
  const [keyword, setKeyword] = useState('')
  const [err, setErr] = useState('')

  async function load(kw = keyword) {
    try {
      const list = await api.records.list(kw)
      setRecords(list)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => { load('') }, [])

  async function toggleRevoke(r: CheatRecord) {
    try {
      await api.records.revoke(r.recordId, !r.revoked)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  const riskColor = (v: number) => (v >= 80 ? 'error' : v >= 60 ? 'warning' : 'success')

  const columns: TableColumnsType<CheatRecord> = [
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '类型', dataIndex: 'cheatType', render: (t: string, r) => <span>{t} <Tag>L{r.level}</Tag></span> },
    { title: '风险', dataIndex: 'riskScore', width: 90, sorter: (a, b) => a.riskScore - b.riskScore, render: (v: number) => <Tag color={riskColor(v)}>{v}</Tag> },
    { title: '结论', dataIndex: 'inspectConclusion', render: (v?: string) => v || '-' },
    {
      title: '状态', dataIndex: 'revoked', width: 100,
      render: (v: boolean) => (v ? <Tag color="default">已撤销</Tag> : <Tag color="error">有效</Tag>),
    },
    { title: '时间', dataIndex: 'occurredAt', width: 180, render: (v?: string) => <span style={{ color: '#8b949e' }}>{formatTime(v)}</span> },
    {
      title: '操作', dataIndex: 'recordId', width: 96,
      render: (_, r) => (
        <Popconfirm
          title={`确定要${r.revoked ? '恢复' : '撤销'}这条记录吗？`}
          onConfirm={() => toggleRevoke(r)}
        >
          <Button danger={!r.revoked} size="small">{r.revoked ? '恢复' : '撤销'}</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>作弊记录</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          按 PTEID 检索作弊命中记录，支持撤销（撤销后不再参与后续判定）
        </Text>
        <div style={{ flex: 1 }} />
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Input
          placeholder="按 PTEID 检索"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={() => load()}
          style={{ width: 320 }}
          allowClear
        />
        <Button type="primary" onClick={() => load()}>检索</Button>
        <Button onClick={() => { setKeyword(''); load('') }}>清空</Button>
      </Space>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<CheatRecord>
          rowKey="recordId"
          columns={columns}
          dataSource={records}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 760 }}
          locale={{ emptyText: '暂无记录' }}
        />
      </Card>
    </div>
  )
}

function formatTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}