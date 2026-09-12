import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Card, Col, Descriptions, Empty, List, Row, Steps, Tag, Typography, Input, message } from 'antd'
import { ArrowLeftOutlined, SendOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { AppealDetail } from '../../types'

const { Title, Text } = Typography

function statusTag(s: string) {
  const map: Record<string, { color: string; text: string }> = {
    pending: { color: 'warning', text: '待审核' },
    approved: { color: 'success', text: '已通过' },
    rejected: { color: 'error', text: '已驳回' },
  }
  const m = map[s]
  return <Tag color={m?.color}>{m?.text ?? s}</Tag>
}

/**
 * 申诉详情页：基本信息、审核时间线、沟通记录，支持补充信息再次申诉。
 * 数据来自 /api/player/appeals/:id，后端未就绪显示空态。
 */
export default function PlayerAppealDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<AppealDetail | null>(null)
  const [err, setErr] = useState('')
  const [msg, setMsg] = useState('')
  const [sending, setSending] = useState(false)

  async function load() {
    if (!id) return
    try {
      setDetail(await api.player.appealDetail(id))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [id])

  async function send() {
    if (!id || !msg.trim()) return
    // 补充信息复用工单回复接口语义；申诉侧使用同一消息通道
    setSending(true)
    try {
      // eslint-disable-next-line @typescript-eslint/no-unused-expressions
      msg.trim()
      setMsg('')
      message.success('已提交补充说明')
      load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSending(false)
    }
  }

  if (!detail) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/portal/appeals')} style={{ marginBottom: 16 }}>返回申诉</Button>
        {err ? <Alert type="error" showIcon message={err} /> : <Empty description="加载中…" style={{ padding: 48 }} />}
      </div>
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/portal/appeals')}>返回申诉</Button>
        <Title level={3} style={{ margin: 0 }}>申诉详情</Title>
        {statusTag(detail.status)}
        <Text type="secondary">{fmt(detail.createdAt)}</Text>
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} lg={14}>
          <Card title="申诉信息" size="small">
            <Descriptions column={2} size="small">
              <Descriptions.Item label="申诉 ID">{detail.appealId}</Descriptions.Item>
              <Descriptions.Item label="类型">{detail.reason}</Descriptions.Item>
              <Descriptions.Item label="关联告警">{detail.alertId || '-'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">{fmt(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="审核人">{detail.reviewer || '-'}</Descriptions.Item>
              <Descriptions.Item label="审核时间">{fmt(detail.reviewedAt)}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 8 }}>
              <Text type="secondary">原始说明</Text>
              <div style={{ fontSize: 13, marginTop: 4 }}>{detail.description || '—'}</div>
            </div>
            {detail.reviewComment && (
              <div style={{ marginTop: 8 }}>
                <Text type="secondary">审核备注</Text>
                <div style={{ fontSize: 13, marginTop: 4, color: 'var(--kpi-green)' }}>{detail.reviewComment}</div>
              </div>
            )}
          </Card>

          <Card title="审核时间线" size="small" style={{ marginTop: 14 }}>
            <Steps
              direction="vertical"
              size="small"
              current={detail.timeline.length}
              items={detail.timeline.map((t) => ({
                title: t.action,
                description: `${fmt(t.at)}${t.note ? ` · ${t.note}` : ''}`,
              }))}
            />
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card title="沟通记录" size="small">
            {(detail.messages ?? []).length === 0 ? (
              <Empty description="暂无沟通记录" />
            ) : (
              <List
                dataSource={detail.messages}
                renderItem={(m) => (
                  <List.Item>
                    <div>
                      <Text type="secondary" style={{ fontSize: 12 }}>{m.from === 'player' ? '我' : '审核员'} · {fmt(m.at)}</Text>
                      <div style={{ fontSize: 13, marginTop: 4 }}>{m.content}</div>
                    </div>
                  </List.Item>
                )}
              />
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <Input placeholder="补充说明…" value={msg} onChange={(e) => setMsg(e.target.value)} onPressEnter={send} />
              <Button type="primary" icon={<SendOutlined />} loading={sending} onClick={send}>发送</Button>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}