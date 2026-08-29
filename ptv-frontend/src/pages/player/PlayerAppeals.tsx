import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { Appeal } from '../../types'

export default function PlayerAppeals() {
  const [appeals, setAppeals] = useState<Appeal[]>([])
  const [err, setErr] = useState('')
  const [ok, setOk] = useState('')
  const [reason, setReason] = useState('误报申诉')
  const [alertId, setAlertId] = useState('')
  const [description, setDescription] = useState('')

  async function load() {
    try {
      setAppeals(await api.player.appeals())
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { load() }, [])

  async function submit() {
    setErr(''); setOk('')
    try {
      await api.player.submitAppeal({ reason, alert_id: alertId, description })
      setOk('已提交申诉，等待审核')
      setAlertId(''); setDescription('')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>在线申诉</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      {ok && <div style={{ color: '#3fb950', marginBottom: 12 }}>{ok}</div>}

      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>提交申诉</h2>
        <select value={reason} onChange={(e) => setReason(e.target.value)} style={input}>
          <option value="误报申诉">误报申诉</option>
          <option value="红屏申诉">红屏申诉</option>
          <option value="查端结果异议">查端结果异议</option>
          <option value="其他">其他</option>
        </select>
        <input placeholder="关联告警 ID（可选）" value={alertId} onChange={(e) => setAlertId(e.target.value)} style={{ ...input, marginTop: 8 }} />
        <textarea placeholder="详细说明（可选）" value={description} onChange={(e) => setDescription(e.target.value)} style={{ ...input, marginTop: 8, minHeight: 70, resize: 'vertical' }} />
        <button style={btnPrimary} onClick={submit}>提交申诉</button>
      </div>

      <div className="card" style={{ padding: 0 }}>
        {appeals.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>暂无申诉记录</div>}
        {appeals.map((a) => (
          <div key={a.appealId} style={{ padding: '12px 16px', borderTop: '1px solid #131920' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <b style={{ fontSize: 13 }}>{a.reason}</b>
              <span style={pill(a.status)}>{statusText(a.status)}</span>
            </div>
            <div style={{ color: '#8b949e', fontSize: 12, marginTop: 4 }}>{a.description}</div>
            <div style={{ color: '#8b949e', fontSize: 11, marginTop: 4 }}>{fmt(a.createdAt)}{a.reviewComment ? ` · 备注: ${a.reviewComment}` : ''}</div>
          </div>
        ))}
      </div>
    </div>
  )
}

function statusText(s: string): string {
  return { pending: '待审核', approved: '已通过', rejected: '已驳回' }[s] ?? s
}
function pill(s: string): CSSProperties {
  const color = s === 'approved' ? '#3fb950' : s === 'rejected' ? '#ff3b30' : '#d29922'
  return { fontSize: 11, padding: '2px 8px', borderRadius: 10, background: color, color: '#0d1117' }
}
function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

const input: CSSProperties = { width: '100%', padding: '10px 12px', margin: '12px 0 0', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3' }
const btnPrimary: CSSProperties = { marginTop: 12, padding: '10px 18px', borderRadius: 6, border: 'none', background: '#ff6b5e', color: '#fff', fontWeight: 600, cursor: 'pointer' }