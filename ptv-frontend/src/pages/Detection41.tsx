import type { CSSProperties } from 'react'
import { useState } from 'react'
import { api } from '../api/client'

interface Verdict {
  cheatType?: string
  displayName?: string
  name?: string
  detected: boolean
  confidence: number
  hits: {
    layer: number
    layerName: string
    signal: string
    weight: number
    type?: string
  }[]
  summary: string
}

interface Analysis {
  edition: string
  feature_dims: number
  brute_force: Verdict[]
  stealth: Verdict[]
  ai_behavior?: { cheat_prob: number; level: string; n_features: number; model?: string }
  ai_human_likeness?: { human_likeness: number; verdict: string }
}

function cheatName(v: Verdict): string {
  return (v as any).displayName || (v as any).name || (v as any).cheatType || '-'
}

function renderDet(badge: string, color: string, detected: boolean, conf: number) {
  return {
    badge,
    color,
    status: detected ? '已判定' : '可疑',
    confText: (conf * 100).toFixed(1) + '%',
  }
}

export default function Detection41() {
  const [result, setResult] = useState<Analysis | null>(null)
  const [err, setErr] = useState('')

  async function run(kind: 'cheat' | 'human' | 'custom', features?: Record<string, number>) {
    setErr('')
    try {
      const res =
        kind === 'cheat'
          ? await api.detection41.demoCheat()
          : kind === 'human'
            ? await api.detection41.demoHuman()
            : await api.detection41.analyze(features ?? {})
      setResult(res)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>v4.1 检测分析引擎</h1>
      <p style={{ color: '#8b949e', marginBottom: 16 }}>
        融合三层引擎：暴力外挂四层递进（信号→时序→物理→语义）· 隐身外挂五层对抗（硬件→内核→内存→网络→行为）· AI 行为画像（128 维）。
      </p>
      <div style={{ marginBottom: 16, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <button style={btnRed} onClick={() => run('cheat')}>一键检测 · 作弊特征演示</button>
        <button style={btn} onClick={() => run('human')}>一键检测 · 人类正常特征</button>
        <button style={btn} onClick={() => run('custom')}>空特征</button>
        {err && <span style={{ color: '#ff3b30', alignSelf: 'center' }}>{err}</span>}
      </div>

      {!result && (
        <div className="card" style={{ color: '#8b949e' }}>点击上方按钮运行检测演示。</div>
      )}

      {result && (
        <div style={{ display: 'grid', gap: 16 }}>
          <div className="card">
            <h2 style={{ fontSize: 16, marginTop: 0 }}>AI 行为画像</h2>
            <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
              <div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>作弊概率 (128 维)</div>
                <div style={{ fontSize: 26, fontWeight: 700 }}>
                  {result.ai_behavior ? (result.ai_behavior.cheat_prob * 100).toFixed(1) + '%' : '-'}
                </div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>
                  等级: <StatusT color={result.ai_behavior?.level === 'cheat' ? '#ff3b30' : '#3fb950'}>
                    {result.ai_behavior?.level ?? '-'}
                  </StatusT>
                  · 维度: {result.ai_behavior?.n_features ?? 0}
                  {result.ai_behavior?.model ? ` · ${result.ai_behavior.model}` : ''}
                </div>
              </div>
              <div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>人类行为模拟度</div>
                <div style={{ fontSize: 26, fontWeight: 700 }}>
                  {result.ai_human_likeness ? (result.ai_human_likeness.human_likeness * 100).toFixed(1) + '%' : '-'}
                </div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>
                  判定: <StatusT color={result.ai_human_likeness?.verdict === 'bot' ? '#ff3b30' : '#3fb950'}>
                    {result.ai_human_likeness?.verdict ?? '-'}
                  </StatusT>
                </div>
              </div>
              <div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>特征维度数</div>
                <div style={{ fontSize: 26, fontWeight: 700 }}>{result.feature_dims}</div>
                <div style={{ color: '#8b949e', fontSize: 12 }}>edition: {result.edition}</div>
              </div>
            </div>
          </div>

          <DetectionCard
            title="暴力外挂（四层递进引擎）"
            verdicts={result.brute_force}
            color="#ff3b30"
          />
          <DetectionCard
            title="隐身外挂（五层对抗引擎）"
            verdicts={result.stealth}
            color="#a371f7"
          />
        </div>
      )}
    </div>
  )
}

function StatusT({ children, color }: { children: React.ReactNode; color: string }) {
  return <span style={{ color, fontWeight: 600 }}>{children}</span>
}

function DetectionCard({ title, verdicts, color }: { title: string; verdicts: Verdict[]; color: string }) {
  return (
    <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #21262d', fontWeight: 600 }}>
        {title}
      </div>
      {verdicts.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>该类别无命中</div>}
      {verdicts.map((v, i) => {
        const r = renderDet('已判定', color, v.detected, v.confidence)
        return (
          <div key={i} style={{ padding: '10px 16px', borderTop: '1px solid #131920' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontWeight: 600 }}>{cheatName(v)}</span>
              <span style={{
                fontSize: 11, padding: '2px 8px', borderRadius: 10,
                background: v.detected ? color : '#21262d',
                color: v.detected ? '#0d1117' : '#8b949e',
              }}>
                {r.status}
              </span>
              <span style={{ color: '#8b949e', fontSize: 12 }}>置信度 {v.confidence.toFixed(2)}</span>
            </div>
            {v.hits.map((h, j) => (
              <div key={j} style={{ color: '#8b949e', fontSize: 12, marginTop: 2, paddingLeft: 10, borderLeft: `2px solid ${color}44` }}>
                L{h.layer} {h.layerName}: {h.signal}
              </div>
            ))}
          </div>
        )
      })}
    </div>
  )
}

const btn: CSSProperties = {
  padding: '9px 16px', borderRadius: 6, border: '1px solid #30363d',
  background: '#161b22', color: '#e6edf3', cursor: 'pointer',
}
const btnRed: CSSProperties = {
  ...btn, background: '#ff3b30', color: '#0d1117', border: 'none', fontWeight: 600,
}