import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { InspectSession } from '../types'
import { StatusPill } from '../components/StatusPill'

export default function Inspect() {
  const [pending, setPending] = useState<InspectSession[]>([])
  const [all, setAll] = useState<InspectSession[]>([])
  const [err, setErr] = useState('')
  const [msg, setMsg] = useState('')

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

  useEffect(() => {
    load()
  }, [])

  async function start(sessionId: string) {
    try {
      await api.inspects.start(sessionId)
      setMsg('已开始查端会话')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function conclude(sessionId: string, conclusion: string) {
    const note = prompt(`提交查端结论：${conclusion === 'confirmed' ? '确认作弊' : '误报'}。备注：`, '')
    if (note === null) return
    try {
      await api.inspects.conclude(sessionId, conclusion, note)
      setMsg(`已完成，结论：${conclusion}`)
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>查端控制台</h1>
      <div style={{ marginBottom: 16 }}>
        <button onClick={load} style={btn}>刷新</button>
        {msg && <span style={{ color: '#3fb950', marginLeft: 12 }}>{msg}</span>}
        {err && <span style={{ color: '#ff3b30', marginLeft: 12 }}>{err}</span>}
      </div>

      <h2 style={{ fontSize: 16 }}>待处理队列</h2>
      <div className="card" style={{ padding: 0, overflowX: 'auto', marginBottom: 24 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ color: '#8b949e', textAlign: 'left' }}>
              <th style={th}>会话 ID</th>
              <th style={th}>PTEID</th>
              <th style={th}>关联警告</th>
              <th style={th}>状态</th>
              <th style={th}>过期时间</th>
              <th style={th}>操作</th>
            </tr>
          </thead>
          <tbody>
            {pending.map((s) => (
              <tr key={s.sessionId} style={{ borderTop: '1px solid #21262d' }}>
                <td style={td}>{s.sessionId}</td>
                <td style={td}>{s.pteid}</td>
                <td style={td}>{s.alertId}</td>
                <td style={td}><StatusPill value={s.state} /></td>
                <td style={td}>{s.expiresAt ? new Date(s.expiresAt).toLocaleString() : '-'}</td>
                <td style={td}>
                  <button style={btnSmall} onClick={() => start(s.sessionId)}>开始查端</button>
                </td>
              </tr>
            ))}
            {pending.length === 0 && (
              <tr>
                <td colSpan={6} style={{ ...td, textAlign: 'center', color: '#8b949e' }}>队列为空</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <h2 style={{ fontSize: 16 }}>全部会话</h2>
      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ color: '#8b949e', textAlign: 'left' }}>
              <th style={th}>PTEID</th>
              <th style={th}>操作员</th>
              <th style={th}>状态</th>
              <th style={th}>结论</th>
              <th style={th}>开始时间</th>
              <th style={th}>操作</th>
            </tr>
          </thead>
          <tbody>
            {all.map((s) => (
              <tr key={s.sessionId} style={{ borderTop: '1px solid #21262d' }}>
                <td style={td}>{s.pteid}</td>
                <td style={td}>{s.operator || '-'}</td>
                <td style={td}><StatusPill value={s.state} /></td>
                <td style={td}>{s.conclusion || '-'}</td>
                <td style={td}>{s.startedAt ? new Date(s.startedAt).toLocaleString() : '-'}</td>
                <td style={td}>
                  {s.state === 'ACTIVE' && (
                    <span style={{ display: 'flex', gap: 6 }}>
                      <button style={btnSmallGreen} onClick={() => conclude(s.sessionId, 'false_positive')}>误报解除</button>
                      <button style={btnSmall} onClick={() => conclude(s.sessionId, 'confirmed')}>确认作弊</button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
            {all.length === 0 && (
              <tr>
                <td colSpan={6} style={{ ...td, textAlign: 'center', color: '#8b949e' }}>无会话</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

const th: CSSProperties = { padding: '10px 14px', fontWeight: 600 }
const td: CSSProperties = { padding: '10px 14px' }
const btn: CSSProperties = {
  padding: '8px 14px', borderRadius: 6, border: '1px solid #30363d',
  background: '#161b22', color: '#e6edf3', cursor: 'pointer',
}
const btnSmall: CSSProperties = { ...btn, padding: '5px 10px', fontSize: 12 }
const btnSmallGreen: CSSProperties = {
  ...btnSmall, background: '#3fb950', color: '#0d1117', border: 'none',
}