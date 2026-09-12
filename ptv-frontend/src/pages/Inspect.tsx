import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Badge, Button, Card, Drawer, List, Modal, Space, Table, Tag, Typography, message } from 'antd'
import { SyncOutlined } from '@ant-design/icons'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { InspectSession } from '../types'
import { StatusPill } from '../components/StatusPill'
import { acceptScreenShare, type SignalTransport } from '../webrtc/webrtc'

const { Title, Text } = Typography

interface Forensics {
  session_id?: string
  os?: string
  java?: string
  cpu_threads?: number
  processes?: { pid: number; name?: string; cpu?: string }[]
}

export default function Inspect() {
  const [pending, setPending] = useState<InspectSession[]>([])
  const [all, setAll] = useState<InspectSession[]>([])
  const [err, setErr] = useState('')
  const [note, setNote] = useState('')
  const [modal, setModal] = useState<{ session: InspectSession; conclusion: 'confirmed' | 'false_positive' } | null>(null)
  const [view, setView] = useState<InspectSession | null>(null)
  const [wsStatus, setWsStatus] = useState<'连接中' | '已连接' | '已断开' | '掉线'>('连接中')
  const [log, setLog] = useState<string[]>([])
  const [forensics, setForensics] = useState<Forensics | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const [screenOn, setScreenOn] = useState(false)

  const load = useCallback(async () => {
    try {
      const [p, a] = await Promise.all([api.inspects.pending(), api.inspects.all()])
      setPending(p)
      setAll(a)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // 打开查端抽屉：建立 /ws/admin 信令通道，实时展示玩家端回传的取证信令与 B2 实时屏幕
  useEffect(() => {
    if (!view) return
    setLog([]); setForensics(null); setScreenOn(false); setWsStatus('连接中')
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}/ws/admin?session_id=${encodeURIComponent(view.sessionId)}`)
    wsRef.current = ws
    // B2：管理端作为被叫，接收玩家端 WebView 的屏幕共享；信令复用本 ws 通道
    const transport: SignalTransport = {
      send: (p) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(p)) },
    }
    const screen = videoRef.current
      ? acceptScreenShare(transport, view.sessionId, videoRef.current)
      : null
    ws.onopen = () => setWsStatus('已连接')
    ws.onclose = () => setWsStatus((s) => (s === '已连接' ? '掉线' : '已断开'))
    ws.onerror = () => setWsStatus('掉线')
    ws.onmessage = (ev) => {
      let msg: unknown
      try { msg = JSON.parse(ev.data as string) } catch { return }
      const record = msg as Record<string, unknown>
      setLog((l) => [...l, JSON.stringify(msg)].slice(-30))
      if (record?.type === 'inspect_started') {
        setLog((l) => [...l, '玩家已连接，等待取证...'])
      } else if (record?.type === 'inspect_forensics') {
        setForensics(record as Forensics)
      } else if (record?.type === 'inspect_offer' || record?.type === 'inspect_ice') {
        setScreenOn(true)
        screen?.onSignal(record).catch(console.error)
      }
    }
    return () => {
      screen?.stop()
      wsRef.current?.close()
      wsRef.current = null
      setScreenOn(false)
    }
  }, [view])

  async function start(session: InspectSession) {
    try {
      await api.inspects.start(session.sessionId)
      message.success('已开始查端会话')
      setView(session)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function doConclude() {
    if (!modal) return
    try {
      await api.inspects.conclude(modal.session.sessionId, modal.conclusion, note)
      message.success(`已完成，结论：${modal.conclusion === 'confirmed' ? '确认作弊' : '误报'}`)
      setModal(null); setNote(''); setView(null)
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
      render: (_, s) => <Button size="small" onClick={() => start(s)}>开始查端</Button>,
    },
  ]

  const allCols: TableColumnsType<InspectSession> = [
    { title: 'PTEID', dataIndex: 'pteid' },
    { title: '操作员', dataIndex: 'operator', render: (v?: string) => v || '-' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '结论', dataIndex: 'conclusion', render: (v?: string) => v || '-' },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    {
      title: '操作', dataIndex: 'state', width: 260,
      render: (_, s) =>
        s.state === 'ACTIVE' ? (
          <Space size={6}>
            <Button size="small" onClick={() => setView(s)}>实时取证</Button>
            <Button size="small" style={{ background: '#3fb950', color: '#0d1117', border: 'none' }} onClick={() => setModal({ session: s, conclusion: 'false_positive' })}>误报解除</Button>
            <Button size="small" danger onClick={() => setModal({ session: s, conclusion: 'confirmed' })}>确认作弊</Button>
          </Space>
        ) : null,
    },
  ]

  const wsColor = wsStatus === '已连接' ? 'success' : wsStatus === '连接中' ? 'processing' : 'error'

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>查端控制台</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>待查端实时取证 · 屏幕共享 · 结论判定</Text>
        <div style={{ flex: 1 }} />
        <Button icon={<SyncOutlined />} onClick={load}>刷新</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

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
          style={{ width: '100%', background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: 8 }}
        />
      </Modal>

      <Drawer
        title={`查端实时信令 · ${view?.sessionId ?? ''}`}
        width={520}
        open={!!view}
        onClose={() => setView(null)}
      >
        <div style={{ marginBottom: 12 }}>
          <Badge status={wsColor} text={wsStatus} />
          <Text type="secondary" style={{ marginLeft: 12 }}>PTEID {view?.pteid}</Text>
        </div>

        <Card size="small" title="实时屏幕" style={{ marginBottom: 12 }}>
          <video
            ref={videoRef}
            muted
            playsInline
            autoPlay
            style={{
              width: '100%',
              aspectRatio: '16 / 9',
              background: '#010409',
              borderRadius: 6,
              display: screenOn ? 'block' : 'none',
            }}
          />
          {!screenOn && (
            <Text type="secondary" style={{ lineHeight: '128px', display: 'block', textAlign: 'center' }}>
              等待玩家端开启屏幕共享...
            </Text>
          )}
        </Card>

        {forensics ? (
          <Card size="small" title="玩家端取证" style={{ marginBottom: 12 }}>
            <p style={{ margin: '4px 0' }}><Text strong>OS：</Text>{forensics.os ?? '-'}</p>
            <p style={{ margin: '4px 0' }}><Text strong>Java：</Text>{forensics.java ?? '-'}</p>
            <p style={{ margin: '4px 0' }}><Text strong>逻辑线程：</Text>{forensics.cpu_threads ?? '-'}</p>
            <List
              size="small"
              bordered
              header={<Text strong>进程快照（按 CPU 排序）</Text>}
              dataSource={forensics.processes ?? []}
              renderItem={(p) => (
                <List.Item style={{ padding: '6px 10px' }}>
                  <span style={{ color: '#e6edf3', fontFamily: 'monospace' }}>{p.pid}</span>
                  <span style={{ flex: 1, margin: '0 8px', color: '#c9d1d9' }}>{p.name ?? '?'}</span>
                  <Tag style={{ margin: 0 }}>{p.cpu ?? '-'}</Tag>
                </List.Item>
              )}
            />
          </Card>
        ) : (
          <Text type="secondary">等待玩家端回传取证...</Text>
        )}

        <Card size="small" title="信令回放" styles={{ body: { padding: 0 } }}>
          <div style={{ maxHeight: 240, overflow: 'auto', padding: '8px 12px', fontFamily: 'monospace', fontSize: 12 }}>
            {log.length === 0 ? (
              <Text type="secondary">暂无信令</Text>
            ) : (
              log.map((l, i) => (
                <div key={i} style={{ color: '#8b949e', padding: '2px 0', wordBreak: 'break-all' }}>{l}</div>
              ))
            )}
          </div>
        </Card>
      </Drawer>
    </div>
  )
}