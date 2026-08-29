import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CompetitionOverview, Enrollment, IpCluster, MatchSession, SuspicionFlag } from '../types'

const kindNames: Record<string, string> = {
  SHARED_ACCOUNT_MULTI_DEVICE: '多设备交替（共享/代练）',
  SHARED_ACCOUNT_MULTI_IP: '多 IP 交替（网络代练/共享）',
  DEVICE_FLAPPING: '设备指纹抖动',
  UNKNOWN: '未知',
}

export default function Competition() {
  const [overview, setOverview] = useState<CompetitionOverview | null>(null)
  const [flags, setFlags] = useState<SuspicionFlag[]>([])
  const [enrollments, setEnrollments] = useState<Enrollment[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [matches, setMatches] = useState<MatchSession[]>([])
  const [clusters, setClusters] = useState<IpCluster[]>([])
  const [keyword, setKeyword] = useState('')
  const [err, setErr] = useState('')

  const [pteid, setPteid] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [tournamentId, setTournamentId] = useState('')

  async function load() {
    try {
      const [ov, fs, es, ms, cs] = await Promise.all([
        api.competition.overview(),
        api.competition.flags(keyword),
        api.competition.enrollments(),
        api.competition.matches(),
        api.competition.ipClusters(),
      ])
      setOverview(ov); setFlags(fs); setEnrollments(es); setMatches(ms); setClusters(cs); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { load() }, [])

  async function review(f: SuspicionFlag, decision: string) {
    if (f.status !== 'OPEN') return
    const comment = window.prompt(`复核${decision === 'ban' ? '（禁赛）' : '（无异常）'}，备注：`, '') ?? ''
    try {
      await api.competition.review(f.flagId, decision, comment)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function enroll() {
    if (!pteid.trim()) return setErr('请填写 PTEID')
    try {
      await api.competition.enroll({ pteid, display_name: displayName, tournament_id: tournamentId })
      setPteid(''); load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function approve(en: Enrollment, ok: boolean) {
    const note = ok ? '' : window.prompt('拒绝原因：', '') ?? ''
    try {
      await api.competition.approve(en.enrollmentId, ok, note)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function startMatch(en: Enrollment, minutes: number) {
    try {
      await api.competition.startMatch({ pteid: en.pteid, tournament_id: en.tournamentId ?? '', duration_minutes: String(minutes) })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function endMatch(m: MatchSession) {
    if (!window.confirm('结束后该选手本场 token 失效，确认结束？')) return
    try {
      await api.competition.endMatch(m.matchId)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function tagTeam(en: Enrollment) {
    const name = window.prompt('分配队伍名（Discord 式标签，如 #Blue2）：', en.teamName ?? '') ?? ''
    if (!name.trim()) return
    const color = window.prompt('队伍徽章颜色（十六进制，如 #58a6ff）：', en.teamColor ?? '#3fb950') ?? '#3fb950'
    try {
      await api.competition.setTeam(en.enrollmentId, { team_name: name.trim(), team_color: color.trim() || '#3fb950' })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  function toggleSel(id: string) {
    setSelected((prev) => {
      const nx = new Set(prev)
      if (nx.has(id)) nx.delete(id); else nx.add(id)
      return nx
    })
  }

  function toggleAll() {
    setSelected(selected.size === enrollments.length ? new Set() : new Set(enrollments.map((e) => e.enrollmentId)))
  }

  async function tagSelected() {
    if (selected.size === 0) return setErr('请先勾选要分队的选手')
    const name = window.prompt(`为选中的 ${selected.size} 人分配队伍名（如 #Blue2）：`, '') ?? ''
    if (!name.trim()) return
    const color = window.prompt('队伍徽章颜色（十六进制，如 #58a6ff）：', '#3fb950') ?? '#3fb950'
    try {
      const r = await api.competition.setTeamBatch({
        enrollment_ids: [...selected],
        team_name: name.trim(),
        team_color: color.trim() || '#3fb950',
      })
      setErr(''); setSelected(new Set()); load()
      window.alert(`已为 ${r.updated} 名选手分配队伍 ${name.trim()}`)
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>赛事风控</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      {overview && (
        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 20 }}>
          <div className="card" style={{ minWidth: 140 }}>
            <div style={{ fontSize: 26, fontWeight: 700, color: '#ff6b5e' }}>{overview.total_flags}</div>
            <div style={{ color: '#8b949e', fontSize: 13 }}>累计嫌疑</div>
          </div>
          {Object.entries(overview.by_kind).map(([k, v]) => (
            <div key={k} className="card" style={{ minWidth: 160 }}>
              <div style={{ fontSize: 22, fontWeight: 700 }}>{v}</div>
              <div style={{ color: '#8b949e', fontSize: 12 }}>{kindNames[k] ?? k}</div>
            </div>
          ))}
        </div>
      )}

      <h2 style={{ fontSize: 16 }}>参赛门禁（报名/审批）</h2>
      <div className="card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }} className="form">
          <input placeholder="PTEID *" value={pteid} onChange={(e) => setPteid(e.target.value)} style={{ ...input, maxWidth: 200 }} />
          <input placeholder="选手名称（可选）" value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={{ ...input, maxWidth: 200 }} />
          <input placeholder="赛事ID（可选）" value={tournamentId} onChange={(e) => setTournamentId(e.target.value)} style={{ ...input, maxWidth: 160 }} />
          <button style={btn} onClick={enroll}>报名</button>
          <span style={{ flex: 1 }} />
          {selected.size > 0 && <span style={{ fontSize: 12, color: '#8b949e' }}>已选 {selected.size} 人</span>}
          <button style={btnGreen} onClick={tagSelected} disabled={selected.size === 0}>批量分配队伍</button>
        </div>
        <div style={{ marginTop: 10 }}>
          <div style={{ padding: '6px 0', borderBottom: '1px solid #21262d', display: 'flex', alignItems: 'center', gap: 12 }}>
            <label style={{ fontSize: 12, color: '#8b949e', display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="checkbox" checked={enrollments.length > 0 && selected.size === enrollments.length} onChange={toggleAll} /> 全选
            </label>
            <span style={{ fontSize: 12, color: '#8b949e' }}>勾选后可批量分到同一队伍</span>
          </div>
          {enrollments.length === 0 && <div style={{ color: '#8b949e' }}>暂无报名记录</div>}
          {enrollments.map((en) => (
            <div key={en.enrollmentId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderTop: '1px solid #131920' }}>
              <input type="checkbox" checked={selected.has(en.enrollmentId)} onChange={() => toggleSel(en.enrollmentId)} />
              <b style={{ width: 90, fontSize: 12 }}>{en.displayName || en.pteid}</b>
              <code style={{ flex: 1, fontSize: 11, color: '#8b949e' }}>{en.pteid} · {en.tournamentId || '通用'}</code>
              <span style={pill(en.status)}>{statusText(en.status)}</span>
              <span style={teamTag(en)}>{en.teamName ? `#${en.teamName}` : '未分队'}</span>
              <span style={{ fontSize: 11, color: '#8b949e' }}>{en.permittedDeviceFingerprint?.slice(0, 16)}…</span>
              <button style={btn} onClick={() => tagTeam(en)}>队伍</button>
          {en.status === 'PENDING' && (
            <button style={btnGreen} onClick={() => approve(en, true)}>通过</button>
          )}
          {en.status === 'APPROVED' && (
            <button style={btn} onClick={() => startMatch(en, 240)}>发起对局(4h)</button>
          )}
          {en.status !== 'REJECTED' && (
            <button style={btnDanger} onClick={() => approve(en, false)}>拒绝</button>
          )}
        </div>
      ))}
        </div>
      </div>

      <h2 style={{ fontSize: 16 }}>对局会话（session token 隔离）</h2>
      <div className="card" style={{ padding: 0, overflowX: 'auto', marginBottom: 20 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: '#8b949e', borderBottom: '1px solid #21262d' }}>
              <th style={th}>PTEID</th><th style={th}>Token（前16位）</th><th style={th}>状态</th>
              <th style={th}>开始</th><th style={th}>过期</th><th style={th}>操作</th>
            </tr>
          </thead>
          <tbody>
            {matches.length === 0 && <tr><td colSpan={6} style={{ padding: 16, color: '#8b949e', textAlign: 'center' }}>暂无对局会话</td></tr>}
            {matches.map((m) => (
              <tr key={m.matchId} style={{ borderTop: '1px solid #131920' }}>
                <td style={td}><b>{m.pteid}</b></td>
                <td style={{ ...td, fontFamily: 'monospace', fontSize: 10, color: '#8b949e' }}>{m.matchId.slice(0, 16)}…</td>
                <td style={td}>
                  <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 10, background: m.status === 'ACTIVE' ? '#3fb950' : '#30363d', color: m.status === 'ACTIVE' ? '#0d1117' : '#8b949e' }}>
                    {m.status === 'ACTIVE' ? '进行中' : '已结束'}
                  </span>
                </td>
                <td style={td}>{fmt(m.startedAt)}</td>
                <td style={td}>{m.expiresAt ? fmt(m.expiresAt) : '-'}</td>
                <td style={td}>{m.status === 'ACTIVE' && <button style={btnDanger} onClick={() => endMatch(m)}>结束</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 style={{ fontSize: 16 }}>IP 聚类（疑似枪手/代练网络）</h2>
      <div className="card" style={{ padding: 0, overflowX: 'auto', marginBottom: 20 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: '#8b949e', borderBottom: '1px solid #21262d' }}>
              <th style={th}>来源 IP</th><th style={th}>账号数</th><th style={th}>关联账号</th><th style={th}>研判</th>
            </tr>
          </thead>
          <tbody>
            {clusters.length === 0 && <tr><td colSpan={4} style={{ padding: 16, color: '#8b949e', textAlign: 'center' }}>无符合条件的 IP 聚类</td></tr>}
            {clusters.map((c) => (
              <tr key={c.ip} style={{ borderTop: '1px solid #131920' }}>
                <td style={{ ...td, fontFamily: 'monospace' }}>{c.ip}</td>
                <td style={td}><b style={{ color: c.account_count >= 3 ? '#ff6b5e' : '#d29922' }}>{c.account_count}</b></td>
                <td style={{ ...td, fontSize: 11, color: '#8b949e', maxWidth: 420 }}>{c.pteids.join(' · ')}</td>
                <td style={td}>
                  <span style={{ fontSize: 11, color: c.account_count >= 3 ? '#ff6b5e' : '#d29922' }}>
                    {c.account_count >= 3 ? '关注：同网多账号' : '提示：同网账号'}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 style={{ fontSize: 16 }}>共享/代练嫌疑（证据哈希链）</h2>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <input placeholder="按 PTEID / 详情检索" value={keyword} onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && load()} style={{ ...input, flex: 1, maxWidth: 360 }} />
        <button style={btn} onClick={load}>检索</button>
      </div>
      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ textAlign: 'left', color: '#8b949e', borderBottom: '1px solid #21262d' }}>
              <th style={th}>PTEID</th><th style={th}>类型</th><th style={th}>权重</th>
              <th style={th}>状态</th><th style={th}>ChainHash</th><th style={th}>时间</th><th style={th}>复核</th>
            </tr>
          </thead>
          <tbody>
            {flags.length === 0 && <tr><td colSpan={7} style={{ padding: 16, color: '#8b949e', textAlign: 'center' }}>暂无嫌疑</td></tr>}
            {flags.map((f) => (
              <tr key={f.flagId} style={{ borderTop: '1px solid #131920' }}>
                <td style={td}><b>{f.pteid}</b></td>
                <td style={td} title={f.detail}>{kindNames[f.kind] ?? f.kind}{f.duringMatch && <span style={{ marginLeft: 6, fontSize: 10, padding: '1px 6px', borderRadius: 9, background: '#ff3b30', color: '#fff' }}>对局中</span>}</td>
                <td style={td}>
                  <span style={{ color: f.weight >= 70 ? '#ff6b5e' : f.weight >= 50 ? '#d29922' : '#3fb950' }}>{f.weight}</span>
                </td>
                <td style={td}>{flagPill(f.status)}</td>
                <td style={{ ...td, fontFamily: 'monospace', fontSize: 10, color: '#8b949e', maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.chainHash}</td>
                <td style={td}>{fmt(f.createdAt)}</td>
                <td style={td}>
                  {f.status === 'OPEN' ? (
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button style={btnGreen} onClick={() => review(f, 'clear')}>无异常</button>
                      <button style={btnDanger} onClick={() => review(f, 'ban')}>禁赛</button>
                    </div>
                  ) : f.reviewer ? <span style={{ fontSize: 11, color: '#8b949e' }}>{f.reviewer}</span> : '-'
                  }
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function statusText(s: string): string {
  return { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' }[s] ?? s
}
function pill(s: string): CSSProperties {
  const color = s === 'APPROVED' ? '#3fb950' : s === 'REJECTED' ? '#ff3b30' : '#d29922'
  return { fontSize: 11, padding: '2px 8px', borderRadius: 10, background: color, color: '#0d1117' }
}
function teamTag(en: Enrollment): CSSProperties {
  return {
    fontSize: 11, padding: '3px 10px', borderRadius: 10, fontWeight: 700,
    background: en.teamName ? (en.teamColor ?? '#3fb950') : '#21262d',
    color: en.teamName ? '#0d1117' : '#8b949e',
  }
}
function flagPill(s: string) {
  const color = s === 'OPEN' ? '#ff3b30' : s === 'ESB' ? '#8e44ad' : '#3fb950'
  const label = s === 'OPEN' ? '待复核' : s === 'ESB' ? 'ESB' : '已复核'
  return <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 10, background: color, color: '#0d1117' }}>{label}</span>
}
function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

const th: CSSProperties = { padding: '9px 12px', fontWeight: 600 }
const td: CSSProperties = { padding: '9px 12px' }
const input: CSSProperties = { padding: '8px 12px', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3' }
const btn: CSSProperties = { padding: '7px 16px', fontSize: 12, borderRadius: 5, border: '1px solid #30363d', background: '#161b22', color: '#e6edf3', cursor: 'pointer' }
const btnGreen: CSSProperties = { ...btn, border: 'none', background: '#3fb950', color: '#0d1117' }
const btnDanger: CSSProperties = { ...btn, border: 'none', background: '#ff3b30', color: '#fff' }