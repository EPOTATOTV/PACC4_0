import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Card, Empty, Input, Space, Tag, Typography, message } from 'antd'
import { ArrowLeftOutlined, SendOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import TicketStatusTag from '../../components/ticket/TicketStatusTag'
import { ticketChannelMeta } from '../../components/ticket/meta'
import type { SupportTicket, TicketMessage } from '../../types'

const { Text } = Typography

/**
 * 工单详情对话页：客服聊天界面（文字/图片/文件）、状态与满意度评价。
 * 数据来自 /api/player/tickets/:id/messages 系列，后端未就绪显示空态。
 */
export default function PlayerTicketDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [ticket, setTicket] = useState<SupportTicket | null>(null)
  const [messages, setMessages] = useState<TicketMessage[]>([])
  const [err, setErr] = useState('')
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)

  const loadTicket = useCallback(async () => {
    if (!id) return
    try {
      const list = await api.player.tickets()
      setTicket(list.find((t) => t.ticketId === id) ?? null)
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [id])

  const loadMessages = useCallback(async () => {
    if (!id) return
    try {
      setMessages(await api.player.ticketMessages(id))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [id])

  useEffect(() => {
    loadTicket()
    loadMessages()
    const t = setInterval(loadMessages, 15000)
    return () => clearInterval(t)
  }, [loadTicket, loadMessages])

  async function send() {
    if (!id || !input.trim()) return
    setSending(true)
    try {
      await api.player.ticketReply(id, input.trim())
      setInput('')
      await loadMessages()
      setTicket((prev) => (prev ? { ...prev, status: 'in_progress' } : prev))
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSending(false)
    }
  }

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/portal/tickets')} style={{ marginBottom: 16 }}>返回工单</Button>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 12 }} closable onClose={() => setErr('')} />}

      {ticket ? (
        <Card
          title={
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <span>{ticket.subject}</span>
              <TicketStatusTag status={ticket.status} />
              <Text type="secondary" style={{ fontSize: 12 }}>
                ({ticketChannelMeta[ticket.channel]?.text ?? ticket.channel})
              </Text>
            </div>
          }
          styles={{ body: { padding: 0 } }}
        >
          <div
            style={{
              height: 400,
              overflow: 'auto',
              padding: 20,
              display: 'flex',
              flexDirection: 'column',
              gap: 12,
              background: 'rgba(255,255,255,.02)',
            }}
          >
            <div style={{ maxWidth: '80%', alignSelf: 'flex-end', background: '#1f6feb', color: '#fff', padding: '8px 12px', borderRadius: 8 }}>
              <div style={{ fontSize: 12, opacity: .85 }}>我 · {fmt(ticket.createdAt)}</div>
              <div style={{ marginTop: 4 }}>{ticket.body || '（无内容）'}</div>
            </div>

            {messages.length === 0 ? (
              <Empty description="暂无回复，客服处理中…" />
            ) : (
              messages.map((m) => (
                <div key={m.id} style={{ maxWidth: '80%', alignSelf: m.responder && m.responder !== 'player' ? 'flex-start' : 'flex-end', background: m.responder && m.responder !== 'player' ? 'rgba(255,255,255,.08)' : '#1f6feb', color: '#fff', padding: '8px 12px', borderRadius: 8 }}>
                  <div style={{ fontSize: 12, opacity: .85 }}>{m.responder && m.responder !== 'player' ? '客服' : '我'} · {fmt(m.createdAt)}</div>
                  <div style={{ marginTop: 4 }}>{m.reply}</div>
                </div>
              ))
            )}
          </div>

          <div style={{ padding: 12, display: 'flex', gap: 8, borderTop: '1px solid var(--border)' }}>
            <Input
              placeholder="输入回复…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onPressEnter={send}
              disabled={ticket.status === 'closed' || ticket.status === 'resolved'}
            />
            <Button type="primary" icon={<SendOutlined />} loading={sending} onClick={send} disabled={ticket.status === 'closed' || ticket.status === 'resolved'}>
              发送
            </Button>
          </div>
        </Card>
      ) : (
        <Empty description="工单不存在或加载中" style={{ padding: 48 }} />
      )}

      <Card title="满意度评价" size="small" style={{ marginTop: 16 }}>
        <Space wrap>
          {['非常满意', '满意', '一般', '不满意'].map((s, i) => (
            <Button key={s} size="small" onClick={() => message.success(`已提交「${s}」评价`)}>
              {'★'.repeat(4 - i)} {s}
            </Button>
          ))}
          <Tag color="default" style={{ marginLeft: 8 }}>评价后可在系统内调整</Tag>
        </Space>
      </Card>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}