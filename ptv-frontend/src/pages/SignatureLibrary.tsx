import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Signature } from '../types'
import { StatusPill } from '../components/StatusPill'

export default function SignatureLibrary() {
  const [edition, setEdition] = useState<'BEDROCK' | 'JAVA'>('BEDROCK')
  const [list, setList] = useState<Signature[]>([])
  const [err, setErr] = useState('')
  const [msg, setMsg] = useState('')

  // 新增表单
  const [name, setName] = useState('')
  const [pattern, setPattern] = useState('')
  const [risk, setRisk] = useState('3')

  async function load(ed = edition) {
    try {
      setList(await api.signatures.list(ed))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [edition])

  async function add() {
    if (!name || !pattern) {
      setErr('名称与特征码必填')
      return
    }
    try {
      await api.signatures.add({
        name, pattern, risk_level: risk, edition, library_version: 'v4.0.0', operator: 'admin',
      })
      setName(''); setPattern('')
      setMsg('特征已加入草稿，需灰度发布后生效')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function gray(percent: number) {
    try {
      await api.signatures.grayRelease(edition, percent)
      setMsg(`已灰度发布至 ${percent}%`)
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function rollback() {
    try {
      const r = await api.signatures.rollback(edition)
      setMsg(`已回滚 ${r.rolled_back} 条`)
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>特征库管理</h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        {(['BEDROCK', 'JAVA'] as const).map((e) => (
          <button
            key={e}
            onClick={() => setEdition(e)}
            style={{
              ...btn, background: edition === e ? '#58a6ff' : '#161b22',
              color: edition === e ? '#0d1117' : '#e6edf3',
            }}
          >
            {e}
          </button>
        ))}
        <button onClick={() => gray(10)} style={btn}>灰度 10%</button>
        <button onClick={() => gray(50)} style={btn}>灰度 50%</button>
        <button onClick={() => gray(100)} style={btn}>全量发布</button>
        <button onClick={rollback} style={{ ...btn, borderColor: '#ff8a80' }}>回滚</button>
        {msg && <span style={{ color: '#3fb950', fontSize: 13 }}>{msg}</span>}
        {err && <span style={{ color: '#ff3b30', fontSize: 13 }}>{err}</span>}
      </div>

      {/* 新增表单 */}
      <div className="card" style={{ marginBottom: 16 }}>
        <h3 style={{ marginTop: 0 }}>新增特征（草稿）</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <input placeholder="特征名称" value={name} onChange={(e) => setName(e.target.value)}
            style={input} />
          <input placeholder="特征码（如 E8 ?? ?? ?? ?? 74 2B）" value={pattern}
            onChange={(e) => setPattern(e.target.value)} style={{ ...input, flex: 2, minWidth: 240 }} />
          <input placeholder="风险等级 1-5" value={risk} onChange={(e) => setRisk(e.target.value)}
            style={{ ...input, width: 90 }} />
          <button onClick={add} style={{ ...btn, background: '#3fb950', color: '#0d1117', border: 'none' }}>
            新增
          </button>
        </div>
      </div>

      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
          <thead>
            <tr style={{ color: '#8b949e', textAlign: 'left' }}>
              <th style={th}>名称</th>
              <th style={th}>特征码</th>
              <th style={th}>风险</th>
              <th style={th}>版本</th>
              <th style={th}>灰度</th>
              <th style={th}>状态</th>
            </tr>
          </thead>
          <tbody>
            {list.map((s) => (
              <tr key={s.id} style={{ borderTop: '1px solid #21262d' }}>
                <td style={td}>{s.name}</td>
                <td style={{ ...td, fontFamily: 'monospace', fontSize: 12 }}>{s.pattern}</td>
                <td style={td}>{s.riskLevel}/5</td>
                <td style={td}>{s.libraryVersion}</td>
                <td style={td}>{s.state === 'GRAY' ? `${s.grayPercent ?? 0}%` : '-'}</td>
                <td style={td}><StatusPill value={s.state} /></td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr>
                <td colSpan={6} style={{ ...td, textAlign: 'center', color: '#8b949e' }}>
                  暂无已发布特征（草稿需通过灰度发布）
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div style={{ marginTop: 8, color: '#8b949e', fontSize: 12 }}>
        说明：新增特征先入草稿（DRAFT），需经灰度发布逐步放量，确认无异常后再全量发布；支持一键回滚。
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