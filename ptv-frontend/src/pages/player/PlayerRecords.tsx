import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { CheatRecord } from '../../types'

export default function PlayerRecords() {
  const [records, setRecords] = useState<CheatRecord[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    api.player.records().then(setRecords).catch((e) => setErr((e as Error).message))
  }, [])

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>我的作弊记录</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: '#8b949e', borderBottom: '1px solid #21262d' }}>
              <th style={th}>类型</th><th style={th}>风险</th><th style={th}>查端结论</th><th style={th}>状态</th><th style={th}>时间</th>
            </tr>
          </thead>
          <tbody>
            {records.length === 0 && (
              <tr><td colSpan={5} style={{ padding: 20, color: '#8b949e', textAlign: 'center' }}>暂无记录</td></tr>
            )}
            {records.map((r) => (
              <tr key={r.recordId} style={{ borderTop: '1px solid #131920' }}>
                <td style={td}>{r.cheatType} <span style={{ color: '#8b949e' }}>L{r.level}</span></td>
                <td style={td}>
                  <span style={{ color: r.riskScore >= 80 ? '#ff3b30' : r.riskScore >= 60 ? '#d29922' : '#3fb950' }}>{r.riskScore}</span>
                </td>
                <td style={td}>{r.inspectConclusion || '-'}</td>
                <td style={td}>
                  <span style={{
                    fontSize: 11, padding: '2px 8px', borderRadius: 10,
                    background: r.revoked ? '#30363d' : '#ff3b30',
                    color: r.revoked ? '#8b949e' : '#fff',
                  }}>
                    {r.revoked ? '已撤销' : '有效'}
                  </span>
                </td>
                <td style={td}>{fmt(r.occurredAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

const th: CSSProperties = { padding: '10px 12px', fontWeight: 600 }
const td: CSSProperties = { padding: '10px 12px' }