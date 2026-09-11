import { useState } from 'react'
import { Alert, Button, Card, Space, Statistic, Typography } from 'antd'
import { api } from '../api/client'

interface Verdict {
  cheatType?: string
  displayName?: string
  name?: string
  detected: boolean
  confidence: number
  hits: { layer: number; layerName: string; signal: string; weight: number; type?: string }[]
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

const { Title, Text } = Typography

function cheatName(v: Verdict): string {
  return (v as any).displayName || (v as any).name || (v as any).cheatType || '-'
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
      <Title level={3} style={{ marginTop: 0 }}>v4.1 检测分析引擎</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        融合三层引擎：暴力外挂四层递进（信号→时序→物理→语义）· 隐身外挂五层对抗（硬件→内核→内存→网络→行为）· AI 行为画像（128 维）。
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" danger onClick={() => run('cheat')}>一键检测 · 作弊特征演示</Button>
        <Button onClick={() => run('human')}>一键检测 · 人类正常特征</Button>
        <Button onClick={() => run('custom')}>空特征</Button>
      </Space>

      {!result && (
        <Card style={{ color: '#8b949e' }}>点击上方按钮运行检测演示。</Card>
      )}

      {result && (
        <div style={{ display: 'grid', gap: 16 }}>
          <Card>
            <h3 style={{ marginTop: 0 }}>AI 行为画像</h3>
            <Space size={40} wrap>
              <div>
                <Statistic
                  title="作弊概率 (128 维)"
                  value={result.ai_behavior ? (result.ai_behavior.cheat_prob * 100).toFixed(1) + '%' : '-'}
                  valueStyle={{ color: result.ai_behavior?.level === 'cheat' ? '#ff3b30' : '#3fb950', fontWeight: 700 }}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  等级: <b style={{ color: result.ai_behavior?.level === 'cheat' ? '#ff3b30' : '#3fb950' }}>{result.ai_behavior?.level ?? '-'}</b>
                  · 维度: {result.ai_behavior?.n_features ?? 0}
                  {result.ai_behavior?.model ? ` · ${result.ai_behavior.model}` : ''}
                </Text>
              </div>
              <div>
                <Statistic
                  title="人类行为模拟度"
                  value={result.ai_human_likeness ? (result.ai_human_likeness.human_likeness * 100).toFixed(1) + '%' : '-'}
                  valueStyle={{ color: result.ai_human_likeness?.verdict === 'bot' ? '#ff3b30' : '#3fb950', fontWeight: 700 }}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  判定: <b style={{ color: result.ai_human_likeness?.verdict === 'bot' ? '#ff3b30' : '#3fb950' }}>{result.ai_human_likeness?.verdict ?? '-'}</b>
                </Text>
              </div>
              <div>
                <Statistic title="特征维度数" value={result.feature_dims} />
                <Text type="secondary" style={{ fontSize: 12 }}>edition: {result.edition}</Text>
              </div>
            </Space>
          </Card>

          <DetectionCard title="暴力外挂（四层递进引擎）" verdicts={result.brute_force} color="#ff3b30" />
          <DetectionCard title="隐身外挂（五层对抗引擎）" verdicts={result.stealth} color="#a371f7" />
        </div>
      )}
    </div>
  )
}

function DetectionCard({ title, verdicts, color }: { title: string; verdicts: Verdict[]; color: string }) {
  return (
    <Card title={title} styles={{ body: { padding: 0 } }}>
      {verdicts.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>该类别无命中</div>}
      {verdicts.map((v, i) => (
        <div key={i} style={{ padding: '12px 16px', borderTop: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span style={{ fontWeight: 600 }}>{cheatName(v)}</span>
            <span style={{
              fontSize: 11, padding: '2px 8px', borderRadius: 10,
              background: v.detected ? color : 'var(--panel-2)',
              color: v.detected ? '#0d1117' : '#8b949e',
            }}>
              {v.detected ? '已判定' : '可疑'}
            </span>
            <span style={{ color: '#8b949e', fontSize: 12 }}>置信度 {v.confidence.toFixed(2)}</span>
          </div>
          {v.hits.map((h, j) => (
            <div key={j} style={{ color: '#8b949e', fontSize: 12, marginTop: 2, paddingLeft: 10, borderLeft: `2px solid ${color}44` }}>
              L{h.layer} {h.layerName}: {h.signal}
            </div>
          ))}
        </div>
      ))}
    </Card>
  )
}