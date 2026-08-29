import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { RedscreenAlert } from '../types'
import { StatusPill } from '../components/StatusPill'

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

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>红屏管理</h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {states.map((s) => (
          <button
            key={s}
            onClick={() => setState(s)}
            style={{
              padding: '8px 14px',
              borderRadius: 6,
              border: '1px solid #30363d',
              background: state === s ? '#ff3b30' : '#161b22',
              color: '#e6edf3',
              cursor: 'pointer',
            }}
          >
            {s}
          </button>
        ))}
      </div>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      <div className="card" style={{ overflowX: 'auto', padding: 0 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ color: '#8b949e', textAlign: 'left' }}>
              <th style={th}>警告 ID</th>
              <th style={th}>级别</th>
              <th style={th}>作弊类型</th>
              <th style={th}>玩家</th>
              <th style={th}>版本</th>
              <th style={th}>风险分</th>
              <th style={th}>广播/送达</th>
              <th style={th}>时间</th>
              <th style={th}>状态</th>
            </tr>
          </thead>
          <tbody>
            {list.map((a) => (
              <tr key={a.alertId} style={{ borderTop: '1px solid #21262d' }}>
                <td style={td}>{a.alertId}</td>
                <td style={td}>{a.level}</td>
                <td style={td}>{a.cheatType}</td>
                <td style={td}>{a.pteidMasked}</td>
                <td style={td}>{a.edition}</td>
                <td style={td}>{a.riskScore}</td>
                <td style={td}>{a.broadcastOnline}/{a.broadcastAck}</td>
                <td style={td}>{a.occurredAt ? new Date(a.occurredAt).toLocaleString() : '-'}</td>
                <td style={td}><StatusPill value={a.state} /></td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr>
                <td colSpan={9} style={{ ...td, color: '#8b949e', textAlign: 'center' }}>
                  暂无 {state} 记录
                </td>
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