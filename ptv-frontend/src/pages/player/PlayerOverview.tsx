import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { MatchValidateResult, PlayerCurrentMatch, PlayerEnrollmentStatus, PlayerSummary } from '../../types'

export default function PlayerOverview() {
  const [summary, setSummary] = useState<PlayerSummary | null>(null)
  const [enroll, setEnroll] = useState<PlayerEnrollmentStatus | null>(null)
  const [match, setMatch] = useState<PlayerCurrentMatch | null>(null)
  const [validate, setValidate] = useState<MatchValidateResult | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.player.summary().then(setSummary).catch((e) => setErr((e as Error).message))
    api.player.myEnrollment().then(setEnroll).catch((e) => setErr((e as Error).message))
    api.player.myCurrentMatch().then(setMatch).catch((e) => setErr((e as Error).message))
  }, [])

  async function doValidate() {
    if (!match?.match) return
    try {
      setValidate(await api.player.validateMatch(match.match.matchId))
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>我的概览</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      {summary && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: '#8b949e' }}>账号 PTEID</div>
            <div style={{ fontSize: 18, fontWeight: 700, fontFamily: 'monospace' }}>{summary.pteid}</div>
          </div>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <Kpi label="作弊记录" value={summary.record_count} color="#ff3b30" />
            <Kpi label="已撤销记录" value={summary.revoked_count} color="#3fb950" />
            <Kpi label="待处理申诉" value={summary.pending_appeals} color="#d29922" />
            <Kpi label="未关闭工单" value={summary.open_tickets} color="#58a6ff" />
          </div>

          {enroll && (
            <div className="card" style={{ marginTop: 16 }}>
              <div style={{ color: '#8b949e', fontSize: 13, marginBottom: 8 }}>赛事状态</div>
              {!enroll.enrolled ? (
                <span style={{ color: '#8b949e' }}>尚未报名参赛</span>
              ) : (
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <Badge ok are>{statusText(enroll.status)}</Badge>
                  <Badge ok={enroll.permitted}>{enroll.permitted ? '当前设备已许可' : '当前设备未许可'}</Badge>
                  <Badge ok={enroll.canEnterMatch}>{enroll.canEnterMatch ? '可进入比赛' : '当前设备未许可，不能进入比赛'}</Badge>
                </div>
              )}
            </div>
          )}

          {match && (
            <div className="card" style={{ marginTop: 16 }}>
              <div style={{ color: '#8b949e', fontSize: 13, marginBottom: 8 }}>对局会话</div>
              {!match.in_match ? (
                <span style={{ color: '#8b949e' }}>当前无进行中的对局</span>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <div style={{ fontSize: 12, color: '#e6edf3' }}>
                    对局 Token（前24位）：<code style={{ color: '#58a6ff' }}>{match.match!.matchId.slice(0, 24)}…</code>
                    <span style={{ color: '#8b949e', marginLeft: 8 }}>过期于 {fmt(match.match!.expiresAt)}</span>
                  </div>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <button style={{ padding: '6px 16px', borderRadius: 5, border: 'none', background: '#238636', color: '#fff', cursor: 'pointer' }} onClick={doValidate}>
                      入场验证（当前设备）
                    </button>
                    {validate && (
                      <span style={{ fontSize: 12, color: validate.allowed ? '#3fb950' : '#ff3b30' }}>
                        {validate.allowed ? '通过，可进入比赛' : validate.message ?? validate.reason}
                      </span>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function Badge({ ok, are, children }: { ok: boolean; are?: boolean; children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 12, padding: '4px 12px', borderRadius: 12,
      background: (are || ok) ? '#3fb950' : '#30363d',
      color: (are || ok) ? '#0d1117' : '#8b949e',
    }}>
      {children}
    </span>
  )
}

function statusText(s?: string): string {
  return { PENDING: '报名待审批', APPROVED: '已报名', REJECTED: '报名被拒' }[s ?? ''] ?? s ?? ''
}

function Kpi({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="card" style={{ minWidth: 160 }}>
      <div style={{ fontSize: 28, fontWeight: 700, color }}>{value}</div>
      <div style={{ color: '#8b949e', fontSize: 13 }}>{label}</div>
    </div>
  )
}