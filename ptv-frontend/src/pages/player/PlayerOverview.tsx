import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd'
import { api } from '../../api/client'
import type { MatchValidateResult, PlayerCurrentMatch, PlayerEnrollmentStatus, PlayerSummary } from '../../types'

const { Title, Text } = Typography

export default function PlayerOverview() {
  const [summary, setSummary] = useState<PlayerSummary | null>(null)
  const [enroll, setEnroll] = useState<PlayerEnrollmentStatus | null>(null)
  const [match, setMatch] = useState<PlayerCurrentMatch | null>(null)
  const [validate, setValidate] = useState<MatchValidateResult | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.player.summary().then(setSummary).catch((e) => setErr((e as Error).message))
    api.player.myEnrollment().then(setEnroll).catch((e) => setErr((e as Error).message))
    api.player.myCurrentMatch().then(setMatch).catch((e) => setErr((e as Error).message))
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

      {summary && (
        <>
          <Card style={{ marginBottom: 16 }}>
            <Text type="secondary">账号 PTEID</Text>
            <div style={{ fontSize: 18, fontWeight: 700, fontFamily: 'monospace', marginTop: 4 }}>
              {summary.pteid}
            </div>
          </Card>

          <Row gutter={[14, 14]}>
            <Col xs={12} sm={6}><StatCard title="作弊记录" value={summary.record_count} color="#ff3b30" /></Col>
            <Col xs={12} sm={6}><StatCard title="已撤销记录" value={summary.revoked_count} color="#3fb950" /></Col>
            <Col xs={12} sm={6}><StatCard title="待处理申诉" value={summary.pending_appeals} color="#d29922" /></Col>
            <Col xs={12} sm={6}><StatCard title="未关闭工单" value={summary.open_tickets} color="#58a6ff" /></Col>
          </Row>

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

function StatCard({ title, value, color }: { title: string; value: number; color: string }) {
  return (
    <Card size="small">
      <Statistic title={title} value={value} valueStyle={{ color, fontWeight: 700 }} />
    </Card>
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