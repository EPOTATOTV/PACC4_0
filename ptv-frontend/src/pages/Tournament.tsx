import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { TournamentNotice, TournamentStage } from '../types'

const kindNames: Record<string, string> = {
  QUALIFIER: '资格赛', GROUP: '小组赛', KNOCKOUT: '淘汰赛', FINAL: '决赛', CUSTOM: '自定义',
}
const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', DONE: '已结束' }

export default function Tournament() {
  const [tournamentId, setTournamentId] = useState('demo-tournament')
  const [tab, setTab] = useState<'stages' | 'notices' | 'register'>('stages')
  const [stages, setStages] = useState<TournamentStage[]>([])
  const [notices, setNotices] = useState<TournamentNotice[]>([])
  const [err, setErr] = useState('')

  // 新增阶段表单
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState('QUALIFIER')
  const [status, setStatus] = useState('PENDING')
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')
  const [note, setNote] = useState('')
  // 公告表单
  const [nTitle, setNTitle] = useState('')
  const [nContent, setNContent] = useState('')
  const [nPinned, setNPinned] = useState(false)
  // 报名配置
  const [cfgTitle, setCfgTitle] = useState('')
  const [docUrl, setDocUrl] = useState('')
  const [deadline, setDeadline] = useState('')
  const [allowRegister, setAllowRegister] = useState(true)

  async function loadConfig() {
    if (!tournamentId.trim()) return
    try {
      const c = await api.competition.config(tournamentId)
      setCfgTitle(c.title ?? ''); setDocUrl(c.tencentDocUrl ?? '')
      setDeadline(toLocalInput(c.applyDeadline ?? '')); setAllowRegister(c.allowRegister)
    } catch (e) { /* 无配置属正常 */ }
  }
  useEffect(() => { load() }, [])
  useEffect(() => { loadConfig() }, [tournamentId])

  async function saveConfig() {
    try {
      await api.competition.updateConfig({
        tournament_id: tournamentId, title: cfgTitle, tencent_doc_url: docUrl,
        apply_deadline: toIso(deadline, 1), allow_register: String(allowRegister),
      })
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }

  async function load() {
    if (!tournamentId.trim()) return
    try {
      const [s, n] = await Promise.all([api.competition.stages(tournamentId), api.competition.notices(tournamentId)])
      setStages(s); setNotices(n); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }

  async function addStage() {
    if (!title.trim()) return setErr('请填写阶段标题')
    try {
      await api.competition.addStage({
        tournament_id: tournamentId, title, kind, status,
        start_time: toIso(startTime, 0), end_time: toIso(endTime, 1), note,
      })
      setTitle(''); setStartTime(''); setEndTime(''); setNote('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function saveStage(s: TournamentStage) {
    const title = window.prompt('阶段标题', s.title) ?? s.title
    const kind = window.prompt(`类别 (${Object.keys(kindNames).join('/')}) [回车保留]`, s.kind) ?? s.kind
    const status = window.prompt(`状态 (PENDING/ACTIVE/DONE) [回车保留]`, s.status) ?? s.status
    const start = window.prompt('开始时间 (YYYY-MM-DD HH:mm，可留空)', s.startTime ? toLocalText(s.startTime) : '') ?? (s.startTime ? toLocalText(s.startTime) : '')
    const end = window.prompt('结束时间 (YYYY-MM-DD HH:mm，可留空)', s.endTime ? toLocalText(s.endTime) : '') ?? (s.endTime ? toLocalText(s.endTime) : '')
    const result = window.prompt('结果/比分（可空）', s.resultNote ?? '') ?? ''
    const note = window.prompt('备注（可空）', s.note ?? '') ?? ''
    const k = kind.trim().toUpperCase()
    const st = status.trim().toUpperCase()
    try {
      await api.competition.updateStage(s.stageId, {
        title: title.trim() || s.title,
        kind: (Object.keys(kindNames) as string[]).includes(k) ? k : s.kind,
        status: (['PENDING', 'ACTIVE', 'DONE'] as string[]).includes(st) ? st : s.status,
        start_time: parseLocalTime(start),
        end_time: parseLocalTime(end),
        result_note: result,
        note,
      })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function move(idx: number, dir: -1 | 1) {
    const to = idx + dir
    if (to < 0 || to >= stages.length) return
    try {
      await api.competition.reorderStage(tournamentId, idx, to)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function toggleStatus(s: TournamentStage) {
    const next = s.status === 'PENDING' ? 'ACTIVE' : s.status === 'ACTIVE' ? 'DONE' : 'PENDING'
    try {
      await api.competition.updateStage(s.stageId, { status: next })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function delStage(s: TournamentStage) {
    if (!window.confirm(`删除阶段「${s.title}」？`)) return
    try { await api.competition.deleteStage(s.stageId); load() } catch (e) { setErr((e as Error).message) }
  }

  async function publish() {
    if (!nTitle.trim()) return setErr('请填写公告标题')
    try {
      await api.competition.publishNotice({ tournament_id: tournamentId, title: nTitle, content: nContent, pinned: nPinned, operator: 'admin' })
      setNTitle(''); setNContent('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function delNotice(n: TournamentNotice) {
    if (!window.confirm(`删除公告「${n.title}」？`)) return
    try { await api.competition.deleteNotice(n.noticeId); load() } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>赛事进程</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <input value={tournamentId} onChange={(e) => setTournamentId(e.target.value)}
          placeholder="赛事 ID" style={{ ...input, maxWidth: 220 }} />
        <button style={btn} onClick={load}>加载该届赛程</button>
        <div style={{ flex: 1 }} />
        <button style={{ ...btn, border: tab === 'stages' ? '1px solid #58a6ff' : undefined }} onClick={() => setTab('stages')}>赛程编排</button>
        <button style={{ ...btn, border: tab === 'notices' ? '1px solid #58a6ff' : undefined }} onClick={() => setTab('notices')}>公告</button>
        <button style={{ ...btn, border: tab === 'register' ? '1px solid #58a6ff' : undefined }} onClick={() => setTab('register')}>报名设置</button>
      </div>

      {tab === 'stages' && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 8 }}>新增阶段（每届赛制不同，自由编排）</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <input placeholder="标题 *" value={title} onChange={(e) => setTitle(e.target.value)} style={{ ...input, maxWidth: 220 }} />
              <select value={kind} onChange={(e) => setKind(e.target.value)} style={input}>
                {Object.entries(kindNames).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <select value={status} onChange={(e) => setStatus(e.target.value)} style={input}>
                {Object.entries(statusNames).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <input placeholder="开始时间(可选)" type="datetime-local" value={startTime} onChange={(e) => setStartTime(e.target.value)} style={input} />
              <input placeholder="结束时间(可选)" type="datetime-local" value={endTime} onChange={(e) => setEndTime(e.target.value)} style={input} />
              <input placeholder="备注(可选)" value={note} onChange={(e) => setNote(e.target.value)} style={{ ...input, maxWidth: 160 }} />
              <button style={btnGreen} onClick={addStage}>添加</button>
            </div>
          </div>

          <div className="card" style={{ padding: 0 }}>
            {stages.length === 0 && <div style={{ padding: 20, color: '#8b949e' }}>该届暂无阶段，先添加一个。</div>}
            {stages.map((s, idx) => (
              <div key={s.stageId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', borderTop: '1px solid #131920' }}>
                <span style={moveBtns}>
                  <button disabled={idx === 0} onClick={() => move(idx, -1)} style={mini}>↑</button>
                  <button disabled={idx === stages.length - 1} onClick={() => move(idx, 1)} style={mini}>↓</button>
                </span>
                <span style={tsPill}>{kindNames[s.kind] ?? s.kind}</span>
                <b style={{ flex: 1 }}>{s.title}</b>
                <span onClick={() => toggleStatus(s)} style={{ ...pill(s.status), cursor: 'pointer' }}>{statusNames[s.status]}</span>
                <span style={{ fontSize: 11, color: '#8b949e', width: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {s.startTime ? fmt(s.startTime) : ''}{s.startTime && s.endTime ? ' → ' : ''}{s.endTime ? fmt(s.endTime) : ''}
                </span>
                <span style={{ fontSize: 11, color: s.resultNote ? '#3fb950' : '#484f58' }}>{s.resultNote || '·'}</span>
                <button style={btn} onClick={() => saveStage(s)}>编辑</button>
                <button style={btnDanger} onClick={() => delStage(s)}>删除</button>
              </div>
            ))}
          </div>
        </>
      )}

      {tab === 'notices' && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 8 }}>发布公告</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <input placeholder="公告标题 *" value={nTitle} onChange={(e) => setNTitle(e.target.value)} style={input} />
              <textarea placeholder="公告内容（赛制说明 / 对阵 / 提醒 / 成绩公示…）" value={nContent}
                onChange={(e) => setNContent(e.target.value)} rows={4} style={{ ...input, resize: 'vertical' }} />
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <label style={{ fontSize: 13, color: '#8b949e', display: 'flex', alignItems: 'center', gap: 6 }}>
                  <input type="checkbox" checked={nPinned} onChange={(e) => setNPinned(e.target.checked)} /> 置顶
                </label>
                <button style={btnGreen} onClick={publish}>发布</button>
              </div>
            </div>
          </div>

          <div className="card" style={{ padding: 0 }}>
            {notices.length === 0 && <div style={{ padding: 20, color: '#8b949e' }}>暂无公告。</div>}
            {notices.map((n) => (
              <div key={n.noticeId} style={{ padding: '12px 16px', borderTop: '1px solid #131920' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {n.pinned && <span style={{ ...tsPill, background: '#8e44ad' }}>置顶</span>}
                  <b>{n.title}</b>
                  <span style={{ flex: 1 }} />
                  <span style={{ fontSize: 11, color: '#8b949e' }}>{fmt(n.createdAt)}</span>
                  <button style={btnDanger} onClick={() => delNotice(n)}>删除</button>
                </div>
                {n.content && <div style={{ marginTop: 6, color: '#c9d1d9', fontSize: 13, whiteSpace: 'pre-wrap' }}>{n.content}</div>}
              </div>
            ))}
          </div>
        </>
      )}
      {tab === 'register' && (
        <div className="card">
          <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 8 }}>报名设置（选手经腾讯文档收集表填资料 → 本平台绑设备 → 提交申请）</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }} className="form">
            <input placeholder="赛事名称（公开展示）" value={cfgTitle} onChange={(e) => setCfgTitle(e.target.value)} style={input} />
            <input placeholder="腾讯文档收集表链接 https://..." value={docUrl} onChange={(e) => setDocUrl(e.target.value)} style={input} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <input type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} style={input} />
              {allowRegister
                ? <span onClick={() => setAllowRegister(false)} style={pill2(true)}>报名中</span>
                : <span onClick={() => setAllowRegister(true)} style={pill2(false)}>已截止</span>}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button style={btnGreen} onClick={saveConfig}>保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function toIso(v: string, end: number): string {
  if (!v) return ''
  const d = new Date(v)
  if (isNaN(d.getTime())) return ''
  if (end === 1) d.setHours(23, 59, 59, 0)
  return d.toISOString()
}
function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function toLocalInput(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

function toLocalText(iso: string): string {
  return toLocalInput(iso).replace('T', ' ')
}

/** 把 'YYYY-MM-DD HH:mm'（或 ''/'-'）解析为 ISO；空或无解析返回 ''。 */
function parseLocalTime(v: string): string {
  const t = (v ?? '').trim()
  if (!t || t === '-') return ''
  const d = new Date(t.replace('T', ' '))
  return isNaN(d.getTime()) ? '' : d.toISOString()
}

const tsPill: CSSProperties = { fontSize: 11, padding: '2px 8px', borderRadius: 10, background: '#21262d', color: '#8b949e' }
const pill = (s: string): CSSProperties => ({
  fontSize: 11, padding: '3px 10px', borderRadius: 10,
  background: s === 'DONE' ? '#3fb950' : s === 'ACTIVE' ? '#d29922' : '#30363d',
  color: s === 'DONE' ? '#0d1117' : s === 'ACTIVE' ? '#0d1117' : '#8b949e',
})
const moveBtns: CSSProperties = { display: 'flex', flexDirection: 'column' }
const mini: CSSProperties = { fontSize: 10, padding: '0 4px', background: 'none', border: '1px solid #30363d', color: '#8b949e', cursor: 'pointer' }
const input: CSSProperties = { padding: '8px 12px', borderRadius: 6, border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3' }
const btn: CSSProperties = { padding: '7px 16px', fontSize: 12, borderRadius: 5, border: '1px solid #30363d', background: '#161b22', color: '#e6edf3', cursor: 'pointer' }
const btnGreen: CSSProperties = { ...btn, border: 'none', background: '#3fb950', color: '#0d1117' }
const btnDanger: CSSProperties = { ...btn, border: 'none', background: '#ff3b30', color: '#fff' }
const pill2 = (on: boolean): CSSProperties => ({
  fontSize: 12, padding: '4px 12px', borderRadius: 12, cursor: 'pointer',
  background: on ? '#3fb950' : '#30363d', color: on ? '#0d1117' : '#8b949e',
})