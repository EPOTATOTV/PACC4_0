import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Col, Input, Row, Space, Switch, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { EChartsOption } from 'echarts'
import { api } from '../../api/client'
import { desktopBridge } from '../../services/desktopBridge'
import type { AiModelStatus, DetectionLogLine, DetectorStatus } from '../../types'
import EChart from '../../components/EChart'
import MetricCard from '../../components/MetricCard'

const { Title, Text } = Typography

function DetectorCard({ d }: { d: DetectorStatus }) {
  const color =
    d.state === 'OK' ? '#3fb950' : d.state === 'DEGRADED' ? '#d29922' : d.state === 'ERROR' ? '#ff3b30' : '#8b949e'
  return (
    <Card size="small" styles={{ body: { padding: '12px 14px' } }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: color, display: 'inline-block' }} />
        <Text strong style={{ flex: 1, fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.name}</Text>
        <Tag color={d.kind === 'violent' ? 'blue' : 'purple'} style={{ fontSize: 10 }}>{d.kind === 'violent' ? '暴力' : '隐身'}</Tag>
      </div>
      <div style={{ marginTop: 8, display: 'flex', gap: 16 }}>
        <Text type="secondary" style={{ fontSize: 11 }}>扫 {d.scanCount}</Text>
        <Text type="secondary" style={{ fontSize: 11 }}>中 {d.hitCount}</Text>
      </div>
    </Card>
  )
}

/**
 * 检测实时监控面板：检测器状态（暴力+隐身）、AI 模型推理状态、实时日志流与性能折线。
 * 数据来自 /api/player/monitor 系列；桌面壳可叠加 client 实时事件，后端未就绪时保持空态。
 */
export default function PlayerMonitor() {
  const [detectors, setDetectors] = useState<DetectorStatus[]>([])
  const [ai, setAi] = useState<AiModelStatus | null>(null)
  const [logs, setLogs] = useState<DetectionLogLine[]>([])
  const [keyword, setKeyword] = useState('')
  const [paused, setPaused] = useState(false)
  const [err, setErr] = useState('')
  const buf = useRef<DetectionLogLine[]>([])

  async function load() {
    try {
      const [d, a, l] = await Promise.all([
        api.player.monitor.detectors(),
        api.player.monitor.ai(),
        api.player.monitor.logs({ limit: 100 }),
      ])
      setDetectors(d)
      setAi(a)
      buf.current = l
      setLogs(l)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  // 桌面壳实时日志追加
  useEffect(() => {
    const off = desktopBridge.onEvent('client_log', (payload) => {
      const line = payload as DetectionLogLine
      buf.current = [...buf.current, line].slice(-200)
      if (!paused) setLogs(buf.current)
    })
    return off
  }, [paused])

  useEffect(() => {
    load()
    const t = setInterval(() => { if (!paused) load() }, 5000)
    return () => clearInterval(t)
  }, [paused])

  const filterLogs = useCallback((list: DetectionLogLine[]) => {
    const k = keyword.trim().toLowerCase()
    if (!k) return list
    return list.filter((l) => l.message.toLowerCase().includes(k) || l.origin.toLowerCase().includes(k))
  }, [keyword])

  const shownLogs = useMemo(() => filterLogs(logs).slice().reverse(), [filterLogs, logs])

  const perfOption: EChartsOption = useMemo(() => {
    const series = detectors.slice(0, 6).map((d) => ({
      name: d.name,
      type: 'line' as const,
      smooth: true,
      symbol: 'none',
      data: [],
    }))
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 12, top: 24, bottom: 24 },
      xAxis: { type: 'category', data: [] as string[], axisLine: { lineStyle: { color: '#8b949e' } }, axisLabel: { color: '#8b949e' } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } }, axisLabel: { color: '#8b949e' } },
      series,
    }
  }, [detectors])

  const levelColor = (l: string) =>
    l === 'ERROR' ? '#ff3b30' : l === 'WARN' ? '#d29922' : l === 'DEBUG' ? '#8b949e' : '#3fb950'

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>实时监控</Title>
        <Text type="secondary">检测器 · AI 模型 · 实时日志</Text>
        <Space style={{ marginLeft: 'auto' }}>
          <Switch checkedChildren="实时" unCheckedChildren="暂停" checked={!paused} onChange={(v) => setPaused(!v)} />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} md={6}><MetricCard label="暴力检测器" value={detectors.filter((d) => d.kind === 'violent').length ?? '-'} hint="个" accent="var(--kpi-blue)" /></Col>
        <Col xs={24} md={6}><MetricCard label="隐身检测器" value={detectors.filter((d) => d.kind === 'stealth').length ?? '-'} hint="个" accent="var(--kpi-purple)" /></Col>
        <Col xs={24} md={6}><MetricCard label="AI 版本" value={ai?.modelVersion ?? '-'} accent="var(--kpi-green)" /></Col>
        <Col xs={24} md={6}><MetricCard label="AI 推理延迟" value={ai ? `${ai.latencyMs}ms` : '-'} accent="var(--kpi-muted)" /></Col>
      </Row>

      <Row gutter={[14, 14]} style={{ marginTop: 14 }}>
        <Col xs={24} xl={8}>
          <Card title="暴力检测器" size="small">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(150px,1fr))', gap: 8 }}>
              {detectors.filter((d) => d.kind === 'violent').length
                ? detectors.filter((d) => d.kind === 'violent').map((d) => <DetectorCard key={d.id} d={d} />)
                : <Text type="secondary" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 24 }}>暂无数据</Text>}
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title="隐身检测器" size="small">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(150px,1fr))', gap: 8 }}>
              {detectors.filter((d) => d.kind === 'stealth').length
                ? detectors.filter((d) => d.kind === 'stealth').map((d) => <DetectorCard key={d.id} d={d} />)
                : <Text type="secondary" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 24 }}>暂无数据</Text>}
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title="AI 模型预测分布" size="small">
            {ai?.predictions?.length ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {ai.predictions.map((p) => (
                  <div key={p.label} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Text style={{ width: 90, fontSize: 12 }}>{p.label}</Text>
                    <div style={{ flex: 1, background: 'rgba(139,148,158,.15)', borderRadius: 4, height: 10, overflow: 'hidden' }}>
                      <div style={{ height: '100%', width: `${Math.min(100, p.count)}%`, background: '#58a6ff' }} />
                    </div>
                    <Text type="secondary" style={{ fontSize: 12 }}>{p.count}</Text>
                  </div>
                ))}
              </div>
            ) : (
              <Text type="secondary">暂无数据</Text>
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[14, 14]} style={{ marginTop: 14 }}>
        <Col xs={24} xl={12}>
          <Card title="检测性能（检测器历史负载）" size="small">
            <EChart option={perfOption} height={240} />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card
            title="实时日志"
            size="small"
            extra={<Input.Search placeholder="搜索日志" allowClear size="small" style={{ width: 200 }} onChange={(e) => setKeyword(e.target.value)} />}
          >
            <div style={{ height: 240, overflow: 'auto', fontFamily: 'monospace', fontSize: 12, lineHeight: 1.7 }}>
              {shownLogs.length === 0 ? (
                <Text type="secondary">暂无日志</Text>
              ) : (
                shownLogs.map((l, i) => {
                  const date = new Date(l.ts).toLocaleTimeString('zh-CN', { hour12: false })
                  return (
                    <div key={`${l.ts}-${i}`}>
                      <Text style={{ color: '#8b949e' }}>[{date}]</Text>{' '}
                      <Text style={{ color: levelColor(l.level), fontWeight: 600 }}>{l.level}</Text>{' '}
                      <Text type="secondary">{l.origin}:</Text> <span>{l.message}</span>
                    </div>
                  )
                })
              )}
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  )
}