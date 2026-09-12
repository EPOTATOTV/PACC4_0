import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Col, Row, Space, Tag, Typography } from 'antd'
import { FullscreenOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import type { EChartsOption } from 'echarts'
import { api } from '../api/client'
import type { MapBanPickSession, RealtimeAlert, RealtimeOverview, RuntimeStat } from '../types'
import EChart from '../components/EChart'
import MetricCard from '../components/MetricCard'
import PageHeader from '../components/PageHeader'

const { Text } = Typography

const bpStatusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }

/**
 * 实时监控大屏：无侧边栏的全屏运营视图。
 * 展示系统概览、运行状态、实时检测事件流、告警列表，以及检测类型/风险分布。
 * 数据来自 /api/admin/realtime 系列，后端未就绪时保持空态。
 */
export default function RealtimeMonitor() {
  const navigate = useNavigate()
  const [overview, setOverview] = useState<RealtimeOverview | null>(null)
  const [runtime, setRuntime] = useState<RuntimeStat[]>([])
  const [events, setEvents] = useState<RealtimeAlert[]>([])
  const [alerts, setAlerts] = useState<RealtimeAlert[]>([])
  const [bpSessions, setBpSessions] = useState<MapBanPickSession[]>([])
  const [err, setErr] = useState('')

  async function load() {
    try {
      const [o, r, e, a, bps] = await Promise.all([
        api.realtime.overview(),
        api.realtime.runtime(),
        api.realtime.events(30),
        api.realtime.alerts(20),
        api.maps.bpSessions().catch(() => []),
      ])
      setOverview(o)
      setRuntime(r)
      setEvents(e)
      setAlerts(a)
      setBpSessions(bps)
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

  function enterFullscreen() {
    if (!document.documentElement.requestFullscreen) return
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {})
    } else {
      document.documentElement.requestFullscreen().catch(() => {})
    }
  }

  const typeDist: EChartsOption = useMemo(() => {
    const counter: Record<string, number> = {}
    events.forEach((e) => { counter[e.type] = (counter[e.type] ?? 0) + 1 })
    const palette = ['#58a6ff', '#d29922', '#ff3b30', '#3fb950', '#a371f7', '#39c5cf']
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, textStyle: { color: '#8b949e' }, type: 'scroll' },
      series: [{
        name: '检测类型',
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        itemStyle: { borderRadius: 4, borderColor: '#101319', borderWidth: 2 },
        label: { color: '#8b949e' },
        data: Object.entries(counter).map(([name, value], i) => ({
          name, value, itemStyle: { color: palette[i % palette.length] },
        })),
      }],
    }
  }, [events])

  const riskDist: EChartsOption = useMemo(() => {
    const riskOf = (e: RealtimeAlert) => e.level * 25
    const high = events.filter((e) => riskOf(e) >= 80).length
    const medium = events.filter((e) => riskOf(e) >= 60 && riskOf(e) < 80).length
    const low = events.filter((e) => riskOf(e) < 60).length
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 12, top: 24, bottom: 24 },
      xAxis: { type: 'category', data: ['高风险', '中风险', '低风险'], axisLine: { lineStyle: { color: '#8b949e' } }, axisLabel: { color: '#8b949e' } },
      yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } }, axisLabel: { color: '#8b949e' } },
      series: [{
        type: 'bar', data: [
          { value: high, itemStyle: { color: '#ff3b30' } },
          { value: medium, itemStyle: { color: '#d29922' } },
          { value: low, itemStyle: { color: '#3fb950' } },
        ],
        barWidth: '42%',
        itemStyle: { borderRadius: [4, 4, 0, 0] },
      }],
    }
  }, [events])

  return (
    <div style={{ minHeight: '100vh', background: 'radial-gradient(1200px 640px at 84% -200px, rgba(255,77,61,.10), transparent 55%), var(--bg)', padding: 20 }}>
      <PageHeader
        title="实时监控大屏"
        description={<Tag color="green" style={{ marginLeft: 2 }}>自动刷新 · 5s</Tag>}
        error={err || undefined}
        onCloseError={() => setErr('')}
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
            <Button icon={<FullscreenOutlined />} onClick={enterFullscreen}>全屏</Button>
          </Space>
        }
      />

      <Row gutter={[12, 12]} className="pacc-stagger">
        <Col xs={12} sm={8} md={4}><MetricCard label="在线玩家" value={overview?.online ?? '-'} accent="var(--kpi-green)" /></Col>
        <Col xs={12} sm={8} md={4}><MetricCard label="今日检测" value={overview?.detections ?? '-'} accent="var(--kpi-blue)" /></Col>
        <Col xs={12} sm={8} md={4}><MetricCard label="今日红屏" value={overview?.redscreenToday ?? '-'} accent="var(--kpi-red)" /></Col>
        <Col xs={12} sm={8} md={4}><MetricCard label="待查端" value={overview?.pendingInspect ?? '-'} accent="var(--kpi-amber)" /></Col>
        <Col xs={12} sm={8} md={4}><MetricCard label="平均风险" value={overview?.avgRisk ?? '-'} accent={(overview?.avgRisk ?? 0) >= 70 ? 'var(--kpi-amber)' : 'var(--kpi-muted)'} /></Col>
        <Col xs={12} sm={8} md={4}><MetricCard label="进行中查端" value={overview?.activeInspect ?? '-'} accent="var(--kpi-muted)" /></Col>
      </Row>

      <Card
        title={<Space size={8}><PlayCircleOutlined style={{ color: 'var(--kpi-blue)' }} />地图 BP 概览</Space>}
        size="small"
        style={{ marginTop: 12 }}
        extra={<Button type="link" size="small" onClick={() => navigate('/maps/bp')}>全部 BP</Button>}
      >
        {bpSessions.length === 0 ? (
          <Text type="secondary">暂无 BP 会话</Text>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            {bpSessions.slice(0, 6).map((s) => {
              const active = s.status === 'ACTIVE' || s.status === 'PAUSED'
              return (
                <Button
                  key={s.bpSessionId}
                  type="text"
                  onClick={() => navigate(`/maps/bp/${s.bpSessionId}`)}
                  style={{
                    height: 'auto', textAlign: 'left', padding: '8px 12px',
                    border: active ? '1px solid var(--kpi-blue)' : '1px solid var(--border)',
                    borderRadius: 8, background: active ? 'rgba(88,166,255,.06)' : 'rgba(255,255,255,.02)',
                    display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 6, fontFamily: 'inherit',
                  }}
                >
                  <span style={{ fontWeight: 600, fontSize: 13 }}>{s.blueTeamName || '蓝'} vs {s.redTeamName || '红'}</span>
                  <span style={{ fontSize: 11, color: 'var(--muted)' }}>
                    <Tag bordered={false} style={{ marginRight: 4, fontSize: 11 }}>{s.format}</Tag>
                    {bpStatusNames[s.status] ?? s.status}
                  </span>
                </Button>
              )
            })}
          </div>
        )}
      </Card>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={8}>
          <Card title="运行状态" size="small">
            {runtime.length === 0 ? (
              <Text type="secondary">暂无运行数据</Text>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {runtime.map((s) => (
                  <div key={s.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ width: 8, height: 8, borderRadius: 4, background: s.status === 'ok' ? '#3fb950' : s.status === 'warn' ? '#d29922' : '#ff3b30', display: 'inline-block' }} />
                    <Text style={{ flex: 1, fontSize: 13 }}>{s.label}</Text>
                    <Text strong style={{ fontFamily: 'monospace' }}>{s.value}{s.unit ? ` ${s.unit}` : ''}</Text>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="检测类型分布" size="small">
            <EChart option={typeDist} height={220} />
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="风险评分分布" size="small">
            <EChart option={riskDist} height={220} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={12}>
          <Card title="实时检测事件流" size="small" styles={{ body: { padding: 0 } }}>
            {events.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center' }}><Text type="secondary">暂无实时事件</Text></div>
            ) : (
              <div style={{ maxHeight: 300, overflow: 'auto' }}>
                {events.map((e) => (
                  <div key={e.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', borderBottom: '1px solid var(--border)' }}>
                    <Tag color={e.level >= 4 ? 'error' : e.level >= 3 ? 'warning' : 'success'}>{e.level}</Tag>
                    <span style={{ flex: 1, fontSize: 13 }}>[{e.type}] {e.message}</span>
                    {e.player && <Text type="secondary" style={{ fontSize: 12 }}>{e.player}</Text>}
                    <Text type="secondary" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>{new Date(e.time).toLocaleTimeString('zh-CN', { hour12: false })}</Text>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="异常告警" size="small" styles={{ body: { padding: 0 } }}>
            {alerts.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center' }}><Text type="secondary">暂无告警</Text></div>
            ) : (
              <div style={{ maxHeight: 300, overflow: 'auto' }}>
                {alerts.map((a) => (
                  <div key={a.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', borderBottom: '1px solid var(--border)' }}>
                    <Tag color={a.level >= 4 ? 'red' : a.level >= 3 ? 'orange' : 'gold'}>L{a.level}</Tag>
                    <span style={{ flex: 1, fontSize: 13 }}>{a.message}</span>
                    <Text type="secondary" style={{ fontSize: 12 }}>{a.time}</Text>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}