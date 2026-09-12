import { useEffect, useMemo, useState } from 'react'
import { Alert, Badge, Button, Card, Col, Progress, Row, Space, Tag, Typography } from 'antd'
import {
  CheckCircleFilled,
  ExclamationCircleFilled,
  PauseCircleFilled,
  PlayCircleFilled,
  ThunderboltFilled,
} from '@ant-design/icons'
import type { EChartsOption } from 'echarts'
import { api } from '../../api/client'
import { desktopBridge, isDesktop } from '../../services/desktopBridge'
import type { DetectionEvent, ProtectionResource, ProtectionStatus, ProtectionStat } from '../../types'
import EChart from '../../components/EChart'

const { Title, Text } = Typography

function fmtDuration(sec?: number): string {
  if (sec == null) return '-'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':')
}

/**
 * 实时保护仪表盘（玩家端首页核心）。
 * 展示保护状态大卡片、引擎状态、实时资源占用、今日统计与最近检测事件流。
 * 桌面壳内通过 desktopBridge 读取实时数据；Web/移动端调用 /api/player/protection 系列，
 * 后端未就绪时保持空态/加载态，不 mock。
 */
export default function PlayerProtection() {
  const [status, setStatus] = useState<ProtectionStatus | null>(null)
  const [stat, setStat] = useState<ProtectionStat | null>(null)
  const [resources, setResources] = useState<ProtectionResource[]>([])
  const [events, setEvents] = useState<DetectionEvent[]>([])
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  async function load() {
    try {
      const [s, st, r, ev] = await Promise.all([
        api.player.protection.status(),
        api.player.protection.stats(),
        api.player.protection.resources(),
        api.player.protection.recentEvents(10),
      ])
      setStatus(s)
      setStat(st)
      setResources(r)
      setEvents(ev)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  // 桌面壳实时事件订阅：status_update / detection_alert
  useEffect(() => {
    if (!isDesktop()) return
    const off1 = desktopBridge.onEvent('status_update', (d) => {
      const s = d as Partial<ProtectionStatus>
      if (s.running != null) setStatus((prev) => (prev ? { ...prev, ...s } : prev))
    })
    const off2 = desktopBridge.onEvent('detection_alert', (d) => {
      const e = d as DetectionEvent
      if (e?.id) setEvents((prev) => [e, ...prev].slice(0, 10))
    })
    return () => {
      off1()
      off2()
    }
  }, [])

  const running = status?.running ?? false
  const state = status?.state ?? 'PAUSED'

  const resourceOption: EChartsOption = useMemo(
    () => ({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 8, top: 24, bottom: 24 },
      xAxis: {
        type: 'category',
        data: resources.map((r) => new Date(r.ts).toLocaleTimeString('zh-CN', { hour12: false })),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#8b949e', fontSize: 10 },
      },
      yAxis: {
        type: 'value',
        max: 100,
        splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } },
        axisLabel: { color: '#8b949e', fontSize: 10, formatter: '{value}%' },
      },
      series: [
        {
          name: 'CPU',
          type: 'line',
          smooth: true,
          symbol: 'none',
          data: resources.map((r) => r.cpu),
          lineStyle: { color: '#58a6ff', width: 2 },
          areaStyle: { color: 'rgba(88,166,255,0.15)' },
        },
        {
          name: '内存',
          type: 'line',
          smooth: true,
          symbol: 'none',
          data: resources.map((r) => r.memory),
          lineStyle: { color: '#3fb950', width: 2 },
        },
        {
          name: '网络',
          type: 'line',
          smooth: true,
          symbol: 'none',
          data: resources.map((r) => r.network),
          lineStyle: { color: '#d29922', width: 2 },
        },
      ],
    }),
    [resources],
  )

  async function act(fn: () => Promise<unknown>) {
    setBusy(true)
    try {
      await fn()
      await load()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const stateBadge = running ? (
    <Badge status="processing" text="保护运行中" />
  ) : state === 'ERROR' ? (
    <Badge status="error" text="检测异常" />
  ) : (
    <Badge status="default" text="保护已暂停" />
  )

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>实时保护</Title>
        {stateBadge}
        {err && <Alert type="error" showIcon message={err} style={{ flex: 1, minWidth: 200 }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} lg={8}>
          <Card
            bordered={false}
            style={{
              background: running
                ? 'radial-gradient(600px 300px at 20% -20%, rgba(63,185,80,.18), transparent 70%)'
                : state === 'ERROR'
                  ? 'radial-gradient(600px 300px at 20% -20%, rgba(255,59,48,.18), transparent 70%)'
                  : 'var(--bg)',
              border: '1px solid var(--border)',
            }}
          >
            <div style={{ fontSize: 44 }}>
              {state === 'ERROR' ? (
                <ExclamationCircleFilled style={{ color: '#ff3b30' }} />
              ) : running ? (
                <CheckCircleFilled style={{ color: '#3fb950' }} />
              ) : (
                <PauseCircleFilled style={{ color: '#8b949e' }} />
              )}
            </div>
            <div style={{ fontSize: 22, fontWeight: 700, marginTop: 8 }}>
              {running ? '保护运行中' : state === 'ERROR' ? '检测异常' : '保护已暂停'}
            </div>
            <Text type="secondary">扫描模式：{status?.mode ?? '-'} · 已扫描 {status?.scanned_regions ?? 0}/{status?.scannable_regions ?? 0} 区域</Text>
            <div style={{ marginTop: 12 }}>
              <Progress
                percent={status?.scannable_regions ? Math.round(((status.scanned_regions ?? 0) / status.scannable_regions) * 100) : 0}
                strokeColor={running ? '#3fb950' : state === 'ERROR' ? '#ff3b30' : '#8b949e'}
                showInfo={false}
              />
            </div>
            <div style={{ marginTop: 16, display: 'flex', gap: 24 }}>
              <div key="up">
                <Text type="secondary">运行时长</Text>
                <div style={{ fontSize: 18, fontWeight: 700, fontFamily: 'monospace' }}>{fmtDuration(status?.uptime_sec)}</div>
              </div>
              <div>
                <Text type="secondary">累计检测</Text>
                <div style={{ fontSize: 18, fontWeight: 700 }}>{status?.detection_count ?? 0}</div>
              </div>
              <div>
                <Text type="secondary">红屏次数</Text>
                <div style={{ fontSize: 18, fontWeight: 700 }}>{status?.redscreen_count ?? 0}</div>
              </div>
            </div>
            <Space style={{ marginTop: 20 }} wrap>
              <Button
                type={running ? 'default' : 'primary'}
                danger={running}
                icon={running ? <PauseCircleFilled /> : <PlayCircleFilled />}
                loading={busy}
                onClick={() => act(() => (running ? api.player.protection.pause() : api.player.protection.resume()))}
              >
                {running ? '暂停保护' : '恢复保护'}
              </Button>
              <Button icon={<ThunderboltFilled />} loading={busy} onClick={() => act(() => api.player.protection.scanNow())}>
                立即扫描
              </Button>
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Row gutter={[14, 14]}>
            <Col xs={12} sm={6}>
              <Card size="small"><StatTile label="今日检测" value={stat?.detections_today ?? 0} color="#58a6ff" /></Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small"><StatTile label="高风险事件" value={stat?.high_risk_today ?? 0} color="#ff3b30" /></Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small"><StatTile label="今日红屏" value={stat?.redscreen_today ?? 0} color="#d29922" /></Card>
            </Col>
            <Col xs={12} sm={6}>
              <Card size="small"><StatTile label="误报" value={stat?.false_positive_today ?? 0} color="#8b949e" /></Card>
            </Col>
            <Col xs={24}>
              <Card title="实时资源占用（近 1 分钟）" size="small">
                <EChart option={resourceOption} height={200} />
              </Card>
            </Col>
          </Row>
        </Col>
      </Row>

      <Card title="最近检测事件" style={{ marginTop: 14 }} styles={{ body: { padding: 0 } }}>
        {events.length === 0 ? (
          <div style={{ padding: 32, textAlign: 'center' }}>
            <Text type="secondary">暂无检测事件，后端数据就绪后将实时展示</Text>
          </div>
        ) : (
          <div style={{ padding: '8px 0' }}>
            {events.map((e) => (
              <div key={e.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 16px', borderBottom: '1px solid var(--border)' }}>
                <Tag color={e.riskScore >= 80 ? 'error' : e.riskScore >= 60 ? 'warning' : 'success'}>
                  {e.riskScore}
                </Tag>
                <span style={{ flex: 1 }}>{e.type}</span>
                {e.player && <Text type="secondary" style={{ fontSize: 12 }}>{e.player}</Text>}
                <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{new Date(e.timestamp).toLocaleTimeString('zh-CN', { hour12: false })}</Text>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

function StatTile({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div>
      <Text type="secondary">{label}</Text>
      <div style={{ fontSize: 24, fontWeight: 700, color, marginTop: 2 }}>{value}</div>
    </div>
  )
}