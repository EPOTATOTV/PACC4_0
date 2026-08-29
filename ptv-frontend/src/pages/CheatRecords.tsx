import { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CheatRecord } from '../types'

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
    if (!window.confirm(`确定要${r.revoked ? '恢复' : '撤销'}这条作弊记录 (${r.recordId}) 吗？`)) return
    try {
      await api.records.revoke(r.recordId, !r.revoked)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>作弊记录</h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input
          placeholder="按 PTEID 检索"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load()}
          style={{ ...input, flex: 1, maxWidth: 360 }}
        />
        <button style={btn} onClick={() => load()}>检索</button>
        <button style={btn} onClick={() => { setKeyword(''); load('') }}>清空</button>
      </div>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: '#8b949e', borderBottom: '1px solid #21262d' }}>
              <th style={th}>PTEID</th>
              <th style={th}>类型</th>
              <th style={th}>风险</th>
              <th style={th}>结论</th>
              <th style={th}>状态</th>
              <th style={th}>时间</th>
              <th style={th}>操作</th>
            </tr>
          </thead>
          <tbody>
            {records.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 20, color: '#8b949e', textAlign: 'center' }}>暂无记录</td></tr>
            )}
            {records.map((r) => (
              <tr key={r.recordId} style={{ borderTop: '1px solid #131920' }}>
                <td style={td}>{r.pteid}</td>
                <td style={td}>{r.cheatType} <span style={{ color: '#8b949e' }}>L{r.level}</span></td>
                <td style={td}>
                  <span style={{ color: r.riskScore >= 80 ? '#ff3b30' : r.riskScore >= 60 ? '#d29922' : '#3fb950' }}>{r.riskScore}</span>
                </td>
                <td style={td}>{r.inspectConclusion || '-'}</td>
                <td style={td}>
                  <StatusPill revoked={r.revoked} />
                </td>
                <td style={td}>{formatTime(r.occurredAt)}</td>
                <td style={td}>
                  <button style={r.revoked ? btn : btnDanger} onClick={() => toggleRevoke(r)}>
                    {r.revoked ? '恢复' : '撤销'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function StatusPill({ revoked }: { revoked: boolean }) {
  return (
    <span style={{
      fontSize: 11, padding: '2px 8px', borderRadius: 10,
      background: revoked ? '#30363d' : '#ff3b30',
      color: revoked ? '#8b949e' : '#fff',
    }}>
      {revoked ? '已撤销' : '有效'}
    </span>
  )
}

function formatTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

const th: CSSProperties = { padding: '10px 12px', fontWeight: 600 }
const td: CSSProperties = { padding: '10px 12px' }
const input: CSSProperties = { padding: '8px 12px', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3' }
const btn: CSSProperties = { padding: '5px 10px', fontSize: 12, borderRadius: 5, border: '1px solid #30363d', background: '#161b22', color: '#e6edf3', cursor: 'pointer' }
const btnDanger: CSSProperties = { ...btn, background: '#ff3b30', color: '#fff', border: 'none' }