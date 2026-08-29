import type { CSSProperties } from 'react'
import { useState } from 'react'
import { api } from '../api/client'
import type { Account } from '../types'
import { StatusPill } from '../components/StatusPill'

export default function Accounts() {
  const [keyword, setKeyword] = useState('')
  const [list, setList] = useState<Account[]>([])
  const [err, setErr] = useState('')

  async function load() {
    try {
      setList(await api.accounts.list(keyword))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>反作弊账号</h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input
          placeholder="按 PTEID 或邮箱检索"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load()}
          style={{ ...input, flex: 1, maxWidth: 360 }}
        />
        <button onClick={load} style={btn}>查询</button>
        {err && <span style={{ color: '#ff3b30' }}>{err}</span>}
      </div>

      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ color: '#8b949e', textAlign: 'left' }}>
              <th style={th}>PTEID</th>
              <th style={th}>邮箱</th>
              <th style={th}>状态</th>
              <th style={th}>信誉分</th>
              <th style={th}>红屏次数</th>
              <th style={th}>注册时间</th>
            </tr>
          </thead>
          <tbody>
            {list.map((a) => (
              <tr key={a.pteid} style={{ borderTop: '1px solid #21262d' }}>
                <td style={{ ...td, fontFamily: 'monospace' }}>{a.pteid}</td>
                <td style={td}>{a.email}</td>
                <td style={td}><StatusPill value={a.status} /></td>
                <td style={td}>{a.reputation}/100</td>
                <td style={td}>{a.totalRedscreen}</td>
                <td style={td}>{a.registeredAt ? new Date(a.registeredAt).toLocaleString() : '-'}</td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr>
                <td colSpan={6} style={{ ...td, textAlign: 'center', color: '#8b949e' }}>无匹配账号</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div style={{ marginTop: 8, color: '#8b949e', fontSize: 12 }}>
        说明：PTEID 为独立反作弊账号，与游戏账号解耦。账号状态由红屏判定与查端结论驱动。
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
const input: CSSProperties = {
  padding: '9px 12px', borderRadius: 6, border: '1px solid #30363d',
  background: '#0d1117', color: '#e6edf3',
}