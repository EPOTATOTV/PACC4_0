import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { PlayerRegisterInfo, TournamentNotice, TournamentStage } from '../../types'

const kindNames: Record<string, string> = {
  QUALIFIER: '资格赛', GROUP: '小组赛', KNOCKOUT: '淘汰赛', FINAL: '决赛', CUSTOM: '自定义',
}
const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', DONE: '已结束' }

export default function PlayerTournament() {
  const [stages, setStages] = useState<TournamentStage[]>([])
  const [notices, setNotices] = useState<TournamentNotice[]>([])
  const [register, setRegister] = useState<PlayerRegisterInfo | null>(null)
  const [fp, setFp] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [err, setErr] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const TOURNAMENT_ID = 'demo-tournament'

  async function loadAll() {
    try {
      const [s, n, r] = await Promise.all([
        api.player.stages(), api.player.notices(), api.player.registerInfo(TOURNAMENT_ID),
      ])
      setStages(s); setNotices(n); setRegister(r); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { loadAll() }, [])

  const st = register?.status
  const enrolled = !!st?.enrolled
  const regStatus = st?.status ?? ''
  const enrollment = register?.status?.enrollment ?? null
  const canSubmit = !!register?.tencent_doc_url && !!fp.trim()
  const active = stages.find((s) => s.status === 'ACTIVE')

  async function submit() {
    if (!register) return
    setSubmitting(true)
    try {
      await api.player.submitRegister({
        tournament_id: register.tournament_id,
        device_fingerprint: fp.trim() || 'demo-device-fingerprint',
        display_name: (displayName.trim() || register.title) ?? '选手',
      })
      setErr(''); await loadAll()
    } catch (e) { setErr((e as Error).message) } finally { setSubmitting(false) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>赛事中心</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}

      {/* 报名引导 */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <b style={{ flex: 1 }}>{register?.title ?? '赛事报名'}</b>
          {register?.allow_register
            ? <span style={{ ...badge, color: '#3fb950' }}>报名中</span>
            : <span style={{ ...badge, color: '#ff3b30' }}>已截止</span>}
          <span style={{ fontSize: 12, color: '#8b949e' }}>
            已报名 {register?.submitted ?? 0} 人{register && register.pending > 0 ? ` / 待审批 ${register.pending}` : ''}
          </span>
        </div>
        {register?.apply_deadline && (
          <div style={{ fontSize: 12, color: '#8b949e', marginTop: 6 }}>报名截止：{fmt(register.apply_deadline)}</div>
        )}

        <div style={{ marginTop: 14, display: 'flex', flexDirection: 'column', gap: 10 }} className="form">
          <div>
            <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 6 }}>第 1 步 · 填写报名资料（腾讯文档收集表）</div>
            {register?.tencent_doc_url ? (
              <a href={register.tencent_doc_url} target="_blank" rel="noreferrer" style={linkBtn}>打开腾讯文档收集表 ↗</a>
            ) : (
              <div style={{ fontSize: 12, color: '#8b949e' }}>主办方尚未配置收集表链接。</div>
            )}
          </div>
          <div>
            <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 6 }}>第 2 步 · 绑定参赛设备（对局将以该设备入场）</div>
            <input placeholder="设备指纹（客户端自动获取，可手动粘贴）" value={fp} onChange={(e) => setFp(e.target.value)} style={input} />
            <input placeholder="参赛昵称（可选）" value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={{ ...input, marginTop: 8 }} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <button style={canSubmit ? btnGreen : { ...btnGreen, opacity: 0.5, cursor: 'not-allowed' }}
              disabled={!canSubmit || submitting} onClick={submit}>
              {submitting ? '提交中…' : '提交参赛申请'}
            </button>
            {!canSubmit && <span style={{ fontSize: 12, color: '#ff3b30' }}>需存在收集表链接且已填写设备指纹</span>}
          </div>
        </div>

        {enrolled && (
          <div style={{ marginTop: 14, padding: '10px 14px', borderRadius: 8, border: '1px solid #21262d', background: '#161b22' }}>
            <div style={{ fontSize: 12, color: '#8b949e', marginBottom: 6 }}>我的报名状态</div>
            {enrollment?.teamName ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '4px 0 8px' }}>
                <span style={{ width: 44, height: 44, borderRadius: 50, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  background: enrollment.teamColor ?? '#3fb950', color: '#0d1117', fontWeight: 700, fontSize: 16 }}>
                  {(enrollment.teamName[0] || 'T').toUpperCase()}
                </span>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <b>{enrollment.teamName}</b>
                    <span style={{ ...badge, background: enrollment.teamColor ?? '#3fb950', color: '#0d1117' }}>队伍成员</span>
                  </div>
                  <div style={{ fontSize: 11, color: '#8b949e', marginTop: 2 }}>{enrollment.displayName || '你'} · 已绑定参赛设备</div>
                </div>
              </div>
            ) : (
              <div style={{ fontSize: 11, color: '#8b949e', marginBottom: 6 }}>主办方尚未为你分配队伍。</div>
            )}
            {regStatus === 'APPROVED' && <div style={{ color: '#3fb950' }}>已通过，你已获得参赛资格。</div>}
            {regStatus === 'PENDING' && <div style={{ color: '#d29922' }}>待审批，请等待主办方审核。</div>}
            {regStatus === 'REJECTED' && <div style={{ color: '#ff3b30' }}>已拒绝{enrollment?.note ? `：${enrollment.note}` : ''}。</div>}
          </div>
        )}
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ color: '#8b949e', fontSize: 13, marginBottom: 10 }}>我的赛程</div>
        {stages.length === 0 ? (
          <div style={{ color: '#8b949e' }}>暂无赛程，等待主办方公布。</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {stages.map((s) => {
              const isActive = s.status === 'ACTIVE'
              const isDone = s.status === 'DONE'
              return (
                <div key={s.stageId} style={{
                  display: 'flex', alignItems: 'center', gap: 14, padding: '12px 14px', borderRadius: 8,
                  border: isActive ? '1px solid #238636' : '1px solid #21262d', background: '#161b22',
                }}>
                  <div style={{ minWidth: 8, height: 40, borderRadius: 4, background: isDone ? '#3fb950' : isActive ? '#d29922' : '#30363d' }} />
                  <span style={badge}>{kindNames[s.kind] ?? s.kind}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600 }}>{s.title}</div>
                    <div style={{ fontSize: 11, color: '#8b949e', marginTop: 2 }}>
                      {s.startTime && <span>{fmt(s.startTime)}</span>}
                      {s.startTime && s.endTime && <span> 至 </span>}
                      {s.endTime && <span>{fmt(s.endTime)}</span>}
                      {s.resultNote && <span style={{ color: '#3fb950' }}> · {s.resultNote}</span>}
                    </div>
                  </div>
                  <span style={pill(s.status)}>{statusNames[s.status]}</span>
                </div>
              )
            })}
          </div>
        )}
        {active && (
          <div style={{ marginTop: 12, fontSize: 13, color: '#d29922' }}>
            当前正处于「{active.title}」，请使用已许可设备、凭对局令牌入场。
          </div>
        )}
      </div>

      <div className="card">
        <div style={{ color: '#8b949e', fontSize: 13, marginBottom: 10 }}>赛事公告</div>
        {notices.length === 0 ? (
          <div style={{ color: '#8b949e' }}>暂无公告。</div>
        ) : (
          notices.map((n) => (
            <div key={n.noticeId} style={{ padding: '12px 0', borderTop: '1px solid #131920' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {n.pinned && <span style={{ ...badge, background: '#8e44ad' }}>置顶</span>}
                <b>{n.title}</b>
                <span style={{ flex: 1 }} />
                <span style={{ fontSize: 11, color: '#8b949e' }}>{fmt(n.createdAt)}</span>
              </div>
              {n.content && <div style={{ marginTop: 6, color: '#c9d1d9', fontSize: 13, whiteSpace: 'pre-wrap' }}>{n.content}</div>}
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}
const badge: CSSProperties = { fontSize: 11, padding: '2px 8px', borderRadius: 10, background: '#21262d', color: '#8b949e' }
const linkBtn: CSSProperties = { display: 'inline-block', padding: '8px 16px', borderRadius: 6, background: '#58a6ff', color: '#0d1117', textDecoration: 'none', fontSize: 13, fontWeight: 600 }
const input: CSSProperties = { width: '100%', padding: '8px 12px', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3', boxSizing: 'border-box' }
const btnGreen: CSSProperties = { padding: '9px 18px', fontSize: 13, borderRadius: 6, border: 'none', background: '#3fb950', color: '#0d1117', cursor: 'pointer' }
const pill = (s: string): CSSProperties => ({
  fontSize: 11, padding: '3px 10px', borderRadius: 10,
  background: s === 'DONE' ? '#3fb950' : s === 'ACTIVE' ? '#d29922' : '#30363d',
  color: s === 'DONE' ? '#0d1117' : s === 'ACTIVE' ? '#0d1117' : '#8b949e',
})