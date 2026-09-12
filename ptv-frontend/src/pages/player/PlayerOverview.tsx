import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd'
import { NotificationOutlined, SafetyCertificateOutlined, SwapOutlined, ThunderboltOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type { Broadcast, MapBanPickSession, MatchValidateResult, PlayerCurrentMatch, PlayerEnrollmentStatus, PlayerSummary } from '../../types'
import MetricCard from '../../components/MetricCard'
import PlayerDetectionPanel from './PlayerDetectionPanel'
import type { ProtectionStatus } from '../../types'

const { Title, Text } = Typography

const bpStatusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }

export default function PlayerOverview() {
  const [summary, setSummary] = useState<PlayerSummary | null>(null)
  const [enroll, setEnroll] = useState<PlayerEnrollmentStatus | null>(null)
  const [match, setMatch] = useState<PlayerCurrentMatch | null>(null)
  const [validate, setValidate] = useState<MatchValidateResult | null>(null)
  const [protection, setProtection] = useState<ProtectionStatus | null>(null)
  const [streams, setStreams] = useState<Broadcast[]>([])
  const [bpSessions, setBpSessions] = useState<MapBanPickSession[]>([])
  const [err, setErr] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    api.player.summary().then(setSummary).catch((e) => setErr((e as Error).message))
    api.player.myEnrollment().then(setEnroll).catch((e) => setErr((e as Error).message))
    api.player.myCurrentMatch().then(setMatch).catch((e) => setErr((e as Error).message))
    api.player.protection.status().then(setProtection).catch(() => {})
    api.player.liveStreams().then(setStreams).catch(() => {})
    api.player.maps.bpCurrent().then((l) => setBpSessions(Array.isArray(l) ? l : [])).catch(() => {})
  }, [])

  async function doValidate() {
    if (!match?.match) return
    try {
      setValidate(await api.player.validateMatch(match.match.matchId))
    } catch (e) { setErr((e as Error).message) }
  }

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>我的概览</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <PlayerDetectionPanel />

      {summary && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,220px) 1fr', gap: 14, marginBottom: 16 }} className="pacc-in">
            <div style={{ border: '1px solid var(--border)', borderRadius: 8, background: 'var(--panel)', padding: '14px 16px' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>账号 PTEID</Text>
              <div style={{ fontSize: 18, fontWeight: 700, fontFamily: 'var(--mono)', marginTop: 4, wordBreak: 'break-all' }}>
                {summary.pteid}
              </div>
            </div>

            {protection && (
              <div style={{ border: '1px solid var(--border)', borderRadius: 8, background: 'var(--panel)', padding: '12px 16px', display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
                <Statistic
                  title="保护状态"
                  value={protection.running ? '运行中' : protection.state === 'ERROR' ? '异常' : '已暂停'}
                  valueStyle={{ color: protection.running ? '#3fb950' : protection.state === 'ERROR' ? '#ff3b30' : '#8b949e', fontSize: 18 }}
                />
                <Statistic title="扫描模式" value={protection.mode || '-'} valueStyle={{ fontSize: 18 }} />
                <Statistic title="累计检测" value={protection.detection_count ?? 0} valueStyle={{ fontSize: 18 }} />
                <Statistic title="当前红屏" value={protection.redscreen_count ?? 0} valueStyle={{ fontSize: 18 }} />
                <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <Button icon={<ThunderboltOutlined />} onClick={() => navigate('/portal/protection')}>前往实时保护</Button>
                  <Button icon={<NotificationOutlined />} onClick={() => navigate('/portal/notifications')}>通知中心</Button>
                  <Button icon={<SafetyCertificateOutlined />} onClick={() => navigate('/portal/security')}>账号安全</Button>
                </div>
              </div>
            )}
          </div>

          <Row gutter={[14, 14]} className="pacc-stagger">
            <Col xs={12} sm={6}><MetricCard label="作弊记录" value={summary.record_count} accent="var(--kpi-red)" /></Col>
            <Col xs={12} sm={6}><MetricCard label="已撤销记录" value={summary.revoked_count} accent="var(--kpi-green)" /></Col>
            <Col xs={12} sm={6}><MetricCard label="待处理申诉" value={summary.pending_appeals} accent="var(--kpi-amber)" /></Col>
            <Col xs={12} sm={6}><MetricCard label="未关闭工单" value={summary.open_tickets} accent="var(--kpi-blue)" /></Col>
          </Row>

          <div className="section-title">赛事与会话</div>

          {enroll && (
            <Card title="赛事状态" style={{ marginTop: 16 }}>
              {!enroll.enrolled ? (
                <Text type="secondary">尚未报名参赛</Text>
              ) : (
                <Space size={8} wrap>
                  <Pill ok are color="success">{enrollText(enroll.status)}</Pill>
                  <Pill ok={enroll.permitted} color="success">{enroll.permitted ? '当前设备已许可' : '当前设备未许可'}</Pill>
                  <Pill ok={enroll.canEnterMatch} color="success">
                    {enroll.canEnterMatch ? '可进入比赛' : '当前设备未许可，不能进入比赛'}
                  </Pill>
                </Space>
              )}
            </Card>
          )}

          {match && (
            <Card title="对局会话" style={{ marginTop: 16 }}>
              {!match.in_match ? (
                <Text type="secondary">当前无进行中的对局</Text>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <div style={{ fontSize: 13 }}>
                    对局 Token（前24位）：<Text code>{match.match!.matchId.slice(0, 24)}…</Text>
                    <Text type="secondary" style={{ marginLeft: 8 }}>过期于 {fmt(match.match!.expiresAt)}</Text>
                  </div>
                  <Space size={12} align="center">
                    <Button type="primary" onClick={doValidate}>入场验证（当前设备）</Button>
                    {validate && (
                      <Tag color={validate.allowed ? 'success' : 'error'}>
                        {validate.allowed ? '通过，可进入比赛' : validate.message ?? validate.reason}
                      </Tag>
                    )}
                  </Space>
                </div>
              )}
            </Card>
          )}

          {streams.length > 0 && (
            <>
              <div className="section-title" style={{ marginTop: 20 }}>赛事直播</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 14, marginTop: 16 }} className="pacc-stagger">
                {streams.map((b) => (
                  <Button
                    key={b.id}
                    type="text"
                    onClick={() => navigate('/portal/stream-live')}
                    style={{
                      height: 'auto', textAlign: 'left', padding: '14px 16px',
                      border: '1px solid var(--border)', borderRadius: 8,
                      background: 'var(--panel)', display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8,
                    }}
                  >
                    <Space size={8}>
                      <VideoCameraOutlined style={{ color: '#ff3b30' }} />
                      <span style={{ fontWeight: 600 }}>{b.title}</span>
                    </Space>
                    <Text type="secondary" style={{ fontSize: 12, fontFamily: 'var(--mono)' }}>
                      live.bilibili.com/{b.bilibili_live_id}
                    </Text>
                  </Button>
                ))}
              </div>
            </>
          )}

          <div className="section-title" style={{ marginTop: 20 }}>地图 BP</div>
          {bpSessions.length === 0 ? (
            <Card style={{ marginTop: 16 }} styles={{ body: { padding: '14px 16px' } }}>
              <Space size={10} align="center" wrap>
                <SwapOutlined style={{ fontSize: 20, color: 'var(--kpi-blue)' }} />
                <div style={{ flex: 1, minWidth: 200 }}>
                  <Text strong style={{ display: 'block' }}>暂无进行中的 BP</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>报名参赛后，裁判发起的选图 BP 会出现在这里，你也可以先浏览地图池。</Text>
                </div>
                <Button onClick={() => navigate('/portal/maps')}>浏览地图池</Button>
              </Space>
            </Card>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 14, marginTop: 16 }} className="pacc-stagger">
              {bpSessions.map((s) => (
                <Button
                  key={s.bpSessionId}
                  type="text"
                  onClick={() => navigate(`/portal/maps/bp/${s.bpSessionId}`)}
                  style={{
                    height: 'auto', textAlign: 'left', padding: '14px 16px',
                    border: s.status === 'ACTIVE' ? '1px solid var(--kpi-blue)' : '1px solid var(--border)',
                    borderRadius: 8, background: s.status === 'ACTIVE' ? 'rgba(88,166,255,.06)' : 'var(--panel)',
                    display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8,
                  }}
                >
                  <Space size={8}>
                    <SwapOutlined style={{ color: 'var(--kpi-blue)' }} />
                    <span style={{ fontWeight: 600 }}>{s.blueTeamName || '蓝方'} vs {s.redTeamName || '红方'}</span>
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    <Tag bordered={false} style={{ marginRight: 6 }}>{s.format}</Tag>
                    {bpStatusNames[s.status] ?? s.status}
                  </Text>
                </Button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function Pill({ ok, are, color, children }: { ok: boolean; are?: boolean; color: string; children: React.ReactNode }) {
  return (
    <Tag color={(are || ok) ? color : 'default'} style={{ marginInlineEnd: 0 }}>
      {children}
    </Tag>
  )
}

function enrollText(s?: string): string {
  return { PENDING: '报名待审批', APPROVED: '已报名', REJECTED: '报名被拒' }[s ?? ''] ?? s ?? ''
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}