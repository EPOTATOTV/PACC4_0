import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { SupportTicket } from '../../types'

const channels = [
  { value: 'ticket', label: '站内工单' },
  { value: 'email', label: '邮件' },
  { value: 'qq', label: 'QQ' },
  { value: 'discord', label: 'Discord' },
]

export default function PlayerTickets() {
  const [tickets, setTickets] = useState<SupportTicket[]>([])
  const [err, setErr] = useState('')
  const [ok, setOk] = useState('')
  const [channel, setChannel] = useState('ticket')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')

  async function load() {
    try {
      setTickets(await api.player.tickets())
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { load() }, [])

  async function submit() {
    setErr(''); setOk('')
    if (!subject.trim()) return setErr('请填写工单主题')
    try {
      await api.player.submitTicket({ channel, subject, body })
      setOk('工单已提交，客服将尽快处理')
      setSubject(''); setBody('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>客服工单</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      {ok && <div style={{ color: '#3fb950', marginBottom: 12 }}>{ok}</div>}

      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>提交工单</h2>
        <select value={channel} onChange={(e) => setChannel(e.target.value)} style={input}>
          {channels.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
        <input placeholder="主题" value={subject} onChange={(e) => setSubject(e.target.value)} style={{ ...input, marginTop: 8 }} />
        <textarea placeholder="问题描述" value={body} onChange={(e) => setBody(e.target.value)} style={{ ...input, marginTop: 8, minHeight: 70, resize: 'vertical' }} />
        <button style={btnPrimary} onClick={submit}>提交工单</button>
      </div>

      <div className="card" style={{ padding: 0 }}>
        {tickets.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>暂无工单</div>}
        {tickets.map((t) => (
          <div key={t.ticketId} style={{ padding: '12px 16px', borderTop: '1px solid #131920' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <b style={{ fontSize: 13 }}>{t.subject}</b>
              <span style={{ color: '#8b949e', fontSize: 11 }}>({t.channel})</span>
              <span style={pill(t.status)}>{statusText(t.status)}</span>
            </div>
            <div style={{ color: '#8b949e', fontSize: 12, marginTop: 4 }}>{t.body}</div>
            <div style={{ color: '#8b949e', fontSize: 11, marginTop: 4 }}>
              {fmt(t.createdAt)}{t.resolution ? ` · 处理结果: ${t.resolution}` : ''}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function statusText(s: string): string {
  return { open: '等待处理', in_progress: '处理中', resolved: '已解决', closed: '已关闭' }[s] ?? s
}
function pill(s: string): CSSProperties {
  const color = s === 'closed' || s === 'resolved' ? '#3fb950' : s === 'in_progress' ? '#d29922' : '#58a6ff'
  return { fontSize: 11, padding: '2px 8px', borderRadius: 10, background: color, color: '#0d1117' }
}
function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

const input: CSSProperties = { width: '100%', padding: '10px 12px', margin: '12px 0 0', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3' }
const btnPrimary: CSSProperties = { marginTop: 12, padding: '10px 18px', borderRadius: 6, border: 'none', background: '#ff6b5e', color: '#fff', fontWeight: 600, cursor: 'pointer' }