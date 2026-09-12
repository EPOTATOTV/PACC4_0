import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Empty, List, Modal, Input, Row, Space, Typography, message, theme } from 'antd'
import { api } from '../api/client'
import MetricCard from '../components/MetricCard'
import TicketList from '../components/ticket/TicketList'
import type { SupportTicket } from '../types'

interface Summary { pending_appeals: number; open_tickets: number; in_progress_tickets: number }

const { Title, Text } = Typography

export default function Compliance() {
  const [summary, setSummary] = useState<Summary | null>(null)
  const [tickets, setTickets] = useState<SupportTicket[]>([])
  const [appeals, setAppeals] = useState<any[]>([])
  const [err, setErr] = useState('')
  const [reviewing, setReviewing] = useState<{ id: string; status: string } | null>(null)
  const [comment, setComment] = useState('')
  const { token } = theme.useToken()

  async function load() {
    try {
      const [sm, tk, ap] = await Promise.all([
        api.compliance.supportSummary(),
        api.compliance.supportTickets('open'),
        api.compliance.supportAppeals('pending'),
      ])
      setSummary(sm)
      setTickets(tk)
      setAppeals(ap)
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }

  useEffect(() => { load() }, [])

  async function doReview() {
    if (!reviewing) return
    try {
      await api.compliance.reviewAppeal(reviewing.id, { status: reviewing.status, reviewer: 'admin', comment })
      message.success(reviewing.status === 'approved' ? '已通过' : '已驳回')
      setReviewing(null); setComment('')
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
      <Title level={3} style={{ marginTop: 0 }}>客服工单</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      {summary && (
        <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
          <Col xs={12} sm={8} md={4}><MetricCard label="待处理申诉" value={summary.pending_appeals} accent="var(--kpi-red)" /></Col>
          <Col xs={12} sm={8} md={4}><MetricCard label="开启工单" value={summary.open_tickets} accent="var(--kpi-blue)" /></Col>
          <Col xs={12} sm={8} md={4}><MetricCard label="处理中工单" value={summary.in_progress_tickets} accent="var(--kpi-amber)" /></Col>
        </Row>
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="待审批申诉" styles={{ body: { padding: 0 } }}>
            {appeals.length === 0 ? <Empty description="无待审申诉" style={{ margin: '16px 0' }} /> : (
              <List
                dataSource={appeals}
                renderItem={(a: any) => (
                  <List.Item style={{ padding: '12px 16px' }} actions={[
                    <Space size={6} key="ops">
                      <Button size="small" style={{ background: '#3fb950', borderColor: 'transparent', color: '#0d1117' }} onClick={() => setReviewing({ id: a.appealId, status: 'approved' })}>通过</Button>
                      <Button size="small" danger onClick={() => setReviewing({ id: a.appealId, status: 'rejected' })}>驳回</Button>
                    </Space>,
                  ]}>
                    <List.Item.Meta
                      title={<Text>{a.pteid} · {a.reason}</Text>}
                      description={<Text type="secondary" style={{ fontSize: 12 }}>{a.description}</Text>}
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="开启工单" styles={{ body: { padding: 0 } }}>
            <TicketList
              tickets={tickets}
              emptyText="无开启工单"
              showPteid
              renderActions={(t) => (
                <Space size={6}>
                  <Button
                    size="small"
                    style={{ background: token.colorSuccess, borderColor: 'transparent', color: '#0d1117' }}
                    onClick={() => transition(t.ticketId, 'in_progress')}
                  >
                    受理
                  </Button>
                  <Button size="small" onClick={() => transition(t.ticketId, 'resolved')}>解决</Button>
                  <Button size="small" onClick={() => transition(t.ticketId, 'closed')}>关闭</Button>
                </Space>
              )}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={reviewing?.status === 'approved' ? '通过申诉' : '驳回申诉'}
        open={!!reviewing}
        okText="提交"
        cancelText="取消"
        onOk={doReview}
        onCancel={() => { setReviewing(null); setComment('') }}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>审批备注</Text>
        <Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="备注（可选）" />
      </Modal>
    </div>
  )
}