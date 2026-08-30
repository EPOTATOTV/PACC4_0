import { useEffect, useState } from 'react'
import { Alert, Button, Card, Modal, Space, Table, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { InspectSession } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title } = Typography

export default function Inspect() {
  const [pending, setPending] = useState<InspectSession[]>([])
  const [all, setAll] = useState<InspectSession[]>([])
  const [err, setErr] = useState('')
  const [note, setNote] = useState('')
  const [modal, setModal] = useState<{ session: InspectSession; conclusion: 'confirmed' | 'false_positive' } | null>(null)

  async function load() {
    try {
      const [p, a] = await Promise.all([api.inspects.pending(), api.inspects.all()])
      setPending(p)
      setAll(a)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => { load() }, [])

  async function start(sessionId: string) {
    try {
      await api.inspects.start(sessionId)
      message.success('已开始查端会话')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function doConclude() {
    if (!modal) return
    try {
      await api.inspects.conclude(modal.session.sessionId, modal.conclusion, note)
      message.success(`已完成，结论：${modal.conclusion === 'confirmed' ? '确认作弊' : '误报'}`)
      setModal(null); setNote('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  const pendingCols: TableColumnsType<InspectSession> = [
    { title: '会话 ID', dataIndex: 'sessionId' },
    { title: 'PTEID', dataIndex: 'pteid' },
    { title: '关联警告', dataIndex: 'alertId' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '过期时间', dataIndex: 'expiresAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    {
      title: '操作', dataIndex: 'sessionId', width: 120,
      render: (_, s) => <Button size="small" onClick={() => start(s.sessionId)}>开始查端</Button>,
    },
  ]

  const allCols: TableColumnsType<InspectSession> = [
    { title: 'PTEID', dataIndex: 'pteid' },
    { title: '操作员', dataIndex: 'operator', render: (v?: string) => v || '-' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '结论', dataIndex: 'conclusion', render: (v?: string) => v || '-' },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    {
      title: '操作', dataIndex: 'state', width: 220,
      render: (_, s) =>
        s.state === 'ACTIVE' ? (
          <Space size={6}>
            <Button size="small" style={{ background: '#3fb950', color: '#0d1117', border: 'none' }} onClick={() => setModal({ session: s, conclusion: 'false_positive' })}>误报解除</Button>
            <Button size="small" danger onClick={() => setModal({ session: s, conclusion: 'confirmed' })}>确认作弊</Button>
          </Space>
        ) : null,
    },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>查端控制台</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Button onClick={load} style={{ marginBottom: 16 }}>刷新</Button>

      <Card title="待处理队列" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        <Table<InspectSession> rowKey="sessionId" columns={pendingCols} dataSource={pending} pagination={false} scroll={{ x: 640 }} locale={{ emptyText: '队列为空' }} />
      </Card>

      <Card title="全部会话" styles={{ body: { padding: 0 } }}>
        <Table<InspectSession> rowKey="sessionId" columns={allCols} dataSource={all} pagination={{ pageSize: 15, hideOnSinglePage: true }} scroll={{ x: 760 }} locale={{ emptyText: '无会话' }} />
      </Card>

      <Modal
        title={`提交查端结论：${modal?.conclusion === 'confirmed' ? '确认作弊' : '误报'}`}
        open={!!modal}
        onOk={doConclude}
        onCancel={() => { setModal(null); setNote('') }}
        okText="提交"
      >
        <p style={{ color: '#8b949e' }}>会话 {modal?.session.sessionId} · PTEID {modal?.session.pteid}</p>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="备注（可选）"
          rows={4}
          style={{ width: '100%', background: '#0d1117', color: '#e6edf3', border: '1px solid #30363d', borderRadius: 6, padding: 8 }}
        />
      </Modal>
    </div>
  )
}