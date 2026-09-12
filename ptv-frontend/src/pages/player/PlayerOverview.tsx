import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd'
import { NotificationOutlined, SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type { MatchValidateResult, PlayerCurrentMatch, PlayerEnrollmentStatus, PlayerSummary } from '../../types'
import MetricCard from '../../components/MetricCard'
import PlayerDetectionPanel from './PlayerDetectionPanel'
import type { ProtectionStatus } from '../../types'

const { Title, Text } = Typography

export default function PlayerOverview() {
  const [summary, setSummary] = useState<PlayerSummary | null>(null)
  const [enroll, setEnroll] = useState<PlayerEnrollmentStatus | null>(null)
  const [match, setMatch] = useState<PlayerCurrentMatch | null>(null)
  const [validate, setValidate] = useState<MatchValidateResult | null>(null)
  const [protection, setProtection] = useState<ProtectionStatus | null>(null)
  const [err, setErr] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    api.player.summary().then(setSummary).catch((e) => setErr((e as Error).message))
    api.player.myEnrollment().then(setEnroll).catch((e) => setErr((e as Error).message))
    api.player.myCurrentMatch().then(setMatch).catch((e) => setErr((e as Error).message))
    api.player.protection.status().then(setProtection).catch(() => {})
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