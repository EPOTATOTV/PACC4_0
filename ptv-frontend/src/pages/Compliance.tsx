import type { CSSProperties } from 'react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'

interface Summary { pending_appeals: number; open_tickets: number; in_progress_tickets: number }

export default function Compliance() {
  const [selfCheck, setSelfCheck] = useState<any>(null)
  const [sla, setSla] = useState<any>(null)
  const [branding, setBranding] = useState<any>(null)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [sbom, setSbom] = useState<any>(null)
  const [tickets, setTickets] = useState<any[]>([])
  const [appeals, setAppeals] = useState<any[]>([])
  const [err, setErr] = useState('')

  async function load() {
    try {
      const [sc, sl, br, sm, sb, tk, ap] = await Promise.all([
        api.compliance.selfCheck(),
        api.compliance.sla(),
        api.compliance.branding(),
        api.compliance.supportSummary(),
        api.compliance.sbom(),
        api.compliance.supportTickets('open'),
        api.compliance.supportAppeals('pending'),
      ])
      setSelfCheck(sc)
      setSla(sl)
      setBranding(br)
      setSummary(sm)
      setSbom(sb)
      setTickets(tk)
      setAppeals(ap)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => { load() }, [])

  async function review(id: string, status: string) {
    const comment = prompt('审批备注：')
    try {
      await api.compliance.reviewAppeal(id, { status, reviewer: 'admin', comment: comment ?? '' })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function transition(id: string, status: string) {
    try {
      await api.compliance.transitionTicket(id, { status, assignee: 'support' })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>合规 · SLA · 客服</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}

      {summary && (
        <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
          <Kpi label="待处理申诉" value={summary.pending_appeals} color="#ff6b5e" />
          <Kpi label="开启工单" value={summary.open_tickets} color="#58a6ff" />
          <Kpi label="处理中工单" value={summary.in_progress_tickets} color="#d29922" />
        </div>
      )}

      {sla && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>SLA 服务等级协议</h2>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', marginBottom: 10 }}>
            <span>可用性: <b>{sla.availability}</b></span>
            <span>误报率目标: <b>{sla.false_positive_target}</b></span>
            <span>新型外挂响应: <b>{sla.new_cheat_response_hours}h</b></span>
          </div>
          {(sla.incident_tiers ?? []).map((t: any) => (
            <div key={t.tier} style={{ fontSize: 13, color: '#8b949e', marginBottom: 4 }}>
              {t.tier} {t.desc} · 响应 {t.sla}
            </div>
          ))}
        </div>
      )}

      {sbom && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>SBOM 物料清单</h2>
          <div style={{ fontSize: 13, color: '#8b949e', display: 'grid', gap: 4 }}>
            <div>前端: <b style={{ color: '#e6edf3' }}>{sbom.frontend}</b></div>
            <div>后端: <b style={{ color: '#e6edf3' }}>{sbom.backend}</b></div>
            <div style={{ color: '#3fb950', fontSize: 12 }}>CycloneDX: {sbom.cyclonedx_license}</div>
          </div>
        </div>
      )}

      {selfCheck && (
        <div className="card" style={{ marginBottom: 16, padding: 0, overflowX: 'auto' }}>
          <div style={{ padding: '12px 16px', fontWeight: 600, borderBottom: '1px solid #21262d' }}>
            合规自检（PIPL / GDPR / 等保 / ISO 27001 / WHQL / SBOM）
          </div>
          {(selfCheck.items ?? []).map((it: any) => (
            <div key={it.code} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 16px', borderTop: '1px solid #131920' }}>
              <span style={{
                fontSize: 11, padding: '2px 8px', borderRadius: 10,
                background: it.ready ? '#3fb950' : '#30363d',
                color: it.ready ? '#0d1117' : '#8b949e',
              }}>{it.ready ? '就绪' : '预留'}</span>
              <span style={{ fontSize: 13 }}>{it.name}</span>
            </div>
          ))}
        </div>
      )}

      {branding && (
        <div className="card" style={{ marginBottom: 16, borderColor: '#a371f7' }}>
          <h2 style={{ fontSize: 15, marginTop: 0, color: '#a371f7' }}>品牌视觉占位（Logo 预留，未改动）</h2>
          <div style={{ fontSize: 13, color: '#8b949e', marginBottom: 8 }}>{branding.note}</div>
          <div style={{ fontSize: 12, color: '#a371f7', marginBottom: 8 }}>状态: {branding.status}</div>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, color: '#8b949e' }}>
            {(branding.placeholder ?? []).map((p: string) => <li key={p}>{p}</li>)}
          </ul>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
          <div style={{ padding: '12px 16px', fontWeight: 600, borderBottom: '1px solid #21262d' }}>待审批申诉</div>
          {appeals.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>无待审申诉</div>}
          {appeals.map((a) => (
            <div key={a.appealId} style={{ padding: '10px 16px', borderTop: '1px solid #131920' }}>
              <div style={{ fontSize: 13 }}>{a.pteid} · {a.reason}</div>
              <div style={{ color: '#8b949e', fontSize: 12 }}>{a.description}</div>
              <div style={{ marginTop: 6, display: 'flex', gap: 6 }}>
                <button style={btnGreen} onClick={() => review(a.appealId, 'approved')}>通过</button>
                <button style={btn} onClick={() => review(a.appealId, 'rejected')}>驳回</button>
              </div>
            </div>
          ))}
        </div>

        <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
          <div style={{ padding: '12px 16px', fontWeight: 600, borderBottom: '1px solid #21262d' }}>开启工单</div>
          {tickets.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>无开启工单</div>}
          {tickets.map((t) => (
            <div key={t.ticketId} style={{ padding: '10px 16px', borderTop: '1px solid #131920' }}>
              <div style={{ fontSize: 13 }}>{t.subject} <span style={{ color: '#8b949e' }}>({t.channel})</span></div>
              <div style={{ color: '#8b949e', fontSize: 12 }}>{t.pteid} · {t.body}</div>
              <div style={{ marginTop: 6, display: 'flex', gap: 6 }}>
                <button style={btnGreen} onClick={() => transition(t.ticketId, 'in_progress')}>受理</button>
                <button style={btn} onClick={() => transition(t.ticketId, 'resolved')}>解决</button>
                <button style={btn} onClick={() => transition(t.ticketId, 'closed')}>关闭</button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function Kpi({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="card" style={{ minWidth: 160 }}>
      <div style={{ fontSize: 28, fontWeight: 700, color }}>{value}</div>
      <div style={{ color: '#8b949e', fontSize: 13 }}>{label}</div>
    </div>
  )
}

const btn: CSSProperties = { padding: '5px 10px', fontSize: 12, borderRadius: 5, border: '1px solid #30363d', background: '#161b22', color: '#e6edf3', cursor: 'pointer' }
const btnGreen: CSSProperties = { ...btn, background: '#3fb950', color: '#0d1117', border: 'none' }