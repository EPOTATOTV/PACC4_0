import { useEffect, useState } from 'react'
import { Alert, Card, Segmented, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { RedscreenAlert } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title } = Typography

export default function Redscreen() {
  const [state, setState] = useState('PENDING_INSPECT')
  const [list, setList] = useState<RedscreenAlert[]>([])
  const [err, setErr] = useState('')

  async function load(s = state) {
    try {
      setList(await api.redscreens.list(s))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [state])

  const states = ['PENDING_INSPECT', 'CONFIRMED', 'FALSE_POSITIVE']

  const columns: TableColumnsType<RedscreenAlert> = [
    { title: '警告 ID', dataIndex: 'alertId' },
    { title: '级别', dataIndex: 'level', width: 90 },
    { title: '作弊类型', dataIndex: 'cheatType' },
    { title: '玩家', dataIndex: 'pteidMasked' },
    { title: '版本', dataIndex: 'edition', width: 110 },
    { title: '风险分', dataIndex: 'riskScore', width: 90 },
    { title: '广播/送达', dataIndex: 'broadcastOnline', width: 110, render: (_, a) => `${a.broadcastOnline}/${a.broadcastAck}` },
    { title: '时间', dataIndex: 'occurredAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    { title: '状态', dataIndex: 'state', width: 130, render: (v: RedscreenAlert['state']) => <StatusPill value={v} /> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>红屏管理</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Segmented
        value={state}
        onChange={(v) => setState(v as string)}
        options={states}
        style={{ marginBottom: 16 }}
      />

      <Card styles={{ body: { padding: 0 } }}>
        <Table<RedscreenAlert>
          rowKey="alertId"
          columns={columns}
          dataSource={list}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 760 }}
          locale={{ emptyText: `暂无 ${state} 记录` }}
        />
      </Card>
    </div>
  )
}