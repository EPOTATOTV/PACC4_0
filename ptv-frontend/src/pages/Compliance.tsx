import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Descriptions, Empty, List, Modal, Input, Row, Space, Statistic, Tag, Typography, message, theme } from 'antd'
import { api } from '../api/client'
import TicketList from '../components/ticket/TicketList'
import type { SupportTicket } from '../types'

interface Summary { pending_appeals: number; open_tickets: number; in_progress_tickets: number }

const { Title, Text } = Typography

export default function Compliance() {
  const [selfCheck, setSelfCheck] = useState<any>(null)
  const [sla, setSla] = useState<any>(null)
  const [branding, setBranding] = useState<any>(null)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [sbom, setSbom] = useState<any>(null)
  const [tickets, setTickets] = useState<SupportTicket[]>([])
  const [appeals, setAppeals] = useState<any[]>([])
  const [err, setErr] = useState('')
  const [reviewing, setReviewing] = useState<{ id: string; status: string } | null>(null)
  const [comment, setComment] = useState('')
  const { token } = theme.useToken()

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
      setSelfCheck(sc); setSla(sl); setBranding(br); setSummary(sm); setSbom(sb)
      setTickets(tk); setAppeals(ap); setErr('')
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
      <Title level={3} style={{ marginTop: 0 }}>合规 · SLA · 客服</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      {summary && (
        <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
          <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="待处理申诉" value={summary.pending_appeals} valueStyle={{ color: '#ff6b5e', fontWeight: 700 }} /></Card></Col>
          <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="开启工单" value={summary.open_tickets} valueStyle={{ color: '#58a6ff', fontWeight: 700 }} /></Card></Col>
          <Col xs={12} sm={8} md={4}><Card size="small"><Statistic title="处理中工单" value={summary.in_progress_tickets} valueStyle={{ color: '#d29922', fontWeight: 700 }} /></Card></Col>
        </Row>
      )}

      {sla && (
        <Card title="SLA 服务等级协议" style={{ marginBottom: 16 }}>
          <Space size={24} wrap style={{ marginBottom: 12 }}>
            <Text>可用性: <Text strong>{sla.availability}</Text></Text>
            <Text>误报率目标: <Text strong>{sla.false_positive_target}</Text></Text>
            <Text>新型外挂响应: <Text strong>{sla.new_cheat_response_hours}h</Text></Text>
          </Space>
          {(sla.incident_tiers ?? []).map((t: any) => (
            <Text type="secondary" key={t.tier} style={{ display: 'block' }}>
              {t.tier} {t.desc} · 响应 {t.sla}
            </Text>
          ))}
        </Card>
      )}

      {sbom && (
        <Card title="SBOM 物料清单" style={{ marginBottom: 16 }}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="前端"><Text strong>{sbom.frontend}</Text></Descriptions.Item>
            <Descriptions.Item label="后端"><Text strong>{sbom.backend}</Text></Descriptions.Item>
            <Descriptions.Item label="许可证"><Text style={{ color: '#3fb950' }}>{sbom.cyclonedx_license}</Text></Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      {selfCheck && (
        <Card title="合规自检（PIPL / GDPR / 等保 / ISO 27001 / WHQL / SBOM）" style={{ marginBottom: 16 }}>
          <List
            dataSource={selfCheck.items ?? []}
            renderItem={(it: any) => (
              <List.Item>
                <Space size={10}>
                  <Tag color={it.ready ? 'success' : 'default'}>{it.ready ? '就绪' : '预留'}</Tag>
                  <Text>{it.name}</Text>
                </Space>
              </List.Item>
            )}
          />
        </Card>
      )}

      {branding && (
        <Card title="品牌视觉" style={{ marginBottom: 16, borderColor: '#a371f7' }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>{branding.note}</Text>
          <Text style={{ color: '#3fb950', display: 'block', marginBottom: 8 }}>
            状态: {branding.status} · Logo 已接入全站页头页脚（英文 Russo One / 中文黑体）
          </Text>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {(branding.placeholder ?? []).map((p: string) => <li key={p}><Text type="secondary" style={{ fontSize: 13 }}>{p}</Text></li>)}
          </ul>
        </Card>
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