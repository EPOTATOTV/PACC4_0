import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Steps, Tag, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type { RedScreenDetail } from '../../types'

const { Title, Text } = Typography

const LEVEL_META: Record<number, { label: string; color: string }> = {
  1: { label: 'L1 轻微', color: 'gold' },
  2: { label: 'L2 中等', color: 'warning' },
  3: { label: 'L3 严重', color: 'volcano' },
  4: { label: 'L4 极重', color: 'red' },
}

/**
 * 红屏事件详情页：基本信息、触发原因（检测类型/风险分/命中检测器）、证据展示、
 * 事件时间线、申诉入口。数据来自 /api/player/redscreen/:id，后端未就绪显示空态。
 */
export default function PlayerRedScreenDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<RedScreenDetail | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!id) return
    let alive = true
    api.player
      .redscreenDetail(id)
      .then((d) => alive && setDetail(d))
      .catch((e) => alive && setErr((e as Error).message))
    return () => {
      alive = false
    }
  }, [id])

  if (!detail) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/portal/records')} style={{ marginBottom: 16 }}>返回记录</Button>
        {err ? (
          <Alert type="error" showIcon message={err} />
        ) : (
          <Empty style={{ padding: 48 }} description="加载中…" />
        )}
      </div>
    )
  }

  const level = LEVEL_META[detail.level] ?? { label: `L${detail.level}`, color: 'default' }

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/portal/records')} style={{ marginBottom: 16 }}>返回记录</Button>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>红屏事件详情</Title>
        <Tag color={level.color}>{level.label}</Tag>
        <Tag>{detail.state}</Tag>
        <Text type="secondary">{new Date(detail.triggeredAt).toLocaleString('zh-CN', { hour12: false })}</Text>
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} lg={14}>
          <Card title="事件信息" size="small">
            <Descriptions column={2} size="small">
              <Descriptions.Item label="事件 ID">{detail.eventId}</Descriptions.Item>
              <Descriptions.Item label="触发时间">{new Date(detail.triggeredAt).toLocaleString('zh-CN', { hour12: false })}</Descriptions.Item>
              <Descriptions.Item label="检测类型">{detail.cheatType}</Descriptions.Item>
              <Descriptions.Item label="风险分">
                <Text style={{ color: detail.riskScore >= 80 ? '#ff3b30' : detail.riskScore >= 60 ? '#d29922' : '#3fb950', fontWeight: 700 }}>
                  {detail.riskScore}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="状态">{detail.state}</Descriptions.Item>
              <Descriptions.Item label="影响玩家">{(detail.hitDetectors ?? []).length > 0 ? '参考下方命中明细' : '暂无'}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="命中检测器" size="small" style={{ marginTop: 14 }}>
            {(detail.hitDetectors ?? []).length === 0 ? (
              <Text type="secondary">暂无命中明细</Text>
            ) : (
              detail.hitDetectors.map((d) => (
                <div key={d.name} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                  <Tag color={d.matched ? 'error' : 'default'}>{d.matched ? '命中' : '未命中'}</Tag>
                  <span style={{ flex: 1 }}>{d.name}</span>
                  <Text type="secondary">风险 {d.risk}</Text>
                </div>
              ))
            )}
          </Card>

          {(detail.evidence?.memory || detail.evidence?.behavior) && (
            <Card title="检测证据" size="small" style={{ marginTop: 14 }}>
              {detail.evidence?.memory && (
                <div style={{ marginBottom: 12 }}>
                  <Text strong>内存特征</Text>
                  <div style={{ fontFamily: 'monospace', fontSize: 12, background: 'rgba(255,255,255,.03)', padding: 8, borderRadius: 6, marginTop: 4 }}>
                    {detail.evidence.memory}
                  </div>
                </div>
              )}
              {detail.evidence?.behavior && (
                <div>
                  <Text strong>行为特征</Text>
                  <div style={{ fontFamily: 'monospace', fontSize: 12, background: 'rgba(255,255,255,.03)', padding: 8, borderRadius: 6, marginTop: 4 }}>
                    {detail.evidence.behavior}
                  </div>
                </div>
              )}
            </Card>
          )}
        </Col>

        <Col xs={24} lg={10}>
          <Card title="事件时间线" size="small">
            <Steps
              direction="vertical"
              size="small"
              current={detail.timeline.length}
              items={detail.timeline.map((t) => ({
                title: t.action,
                description: `${new Date(t.at).toLocaleTimeString('zh-CN', { hour12: false })}${t.note ? ` · ${t.note}` : ''}`,
              }))}
            />
          </Card>

          {detail.player && (
            <Card title="玩家信息" size="small" style={{ marginTop: 14 }}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="PTEID">{detail.player.pteid}</Descriptions.Item>
                <Descriptions.Item label="信誉分">{detail.player.reputation}</Descriptions.Item>
                <Descriptions.Item label="设备">{detail.player.device || '-'}</Descriptions.Item>
              </Descriptions>
              <Button type="primary" block style={{ marginTop: 12 }}>
                前往申诉
              </Button>
            </Card>
          )}
        </Col>
      </Row>
    </div>
  )
}