import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Space, Steps, Tag, Typography, message } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { RedScreenDetail } from '../types'

const { Title, Text } = Typography

const LEVEL_META: Record<number, { label: string; color: string }> = {
  1: { label: 'L1', color: 'gold' },
  2: { label: 'L2', color: 'warning' },
  3: { label: 'L3', color: 'volcano' },
  4: { label: 'L4', color: 'red' },
}

/**
 * 管理端红屏事件详情页：事件信息、命中检测器、证据、时间线、操作面板。
 * 数据来自 /api/admin/redscreens/:id，后端未就绪显示空态。
 */
export default function RedScreenDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [d, setD] = useState<RedScreenDetail | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!id) return
    api.redscreenAdminDetail(id).then(setD).catch((e) => setErr((e as Error).message))
  }, [id])

  if (err) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/redscreen')} style={{ marginBottom: 16 }}>返回红屏列表</Button>
        <Alert type="error" showIcon message={err} />
      </div>
    )
  }

  if (!d) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/redscreen')} style={{ marginBottom: 16 }}>返回红屏列表</Button>
        <Empty description="加载中…" style={{ padding: 48 }} />
      </div>
    )
  }

  const level = LEVEL_META[d.level] ?? { label: `L${d.level}`, color: 'default' }

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/redscreen')} style={{ marginBottom: 16 }}>返回红屏列表</Button>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>红屏事件详情</Title>
        <Tag color={level.color}>{level.label}</Tag>
        <Tag>{d.state}</Tag>
        <Text type="secondary">{new Date(d.triggeredAt).toLocaleString('zh-CN', { hour12: false })}</Text>
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} lg={15}>
          <Card title="事件信息" size="small">
            <Descriptions column={2} size="small">
              <Descriptions.Item label="事件 ID">{d.eventId}</Descriptions.Item>
              <Descriptions.Item label="检测类型">{d.cheatType}</Descriptions.Item>
              <Descriptions.Item label="风险分">
                <Text style={{ color: d.riskScore >= 80 ? '#ff3b30' : d.riskScore >= 60 ? '#d29922' : '#3fb950', fontWeight: 700 }}>{d.riskScore}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="触发时间">{fmt(d.triggeredAt)}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="命中检测器" size="small" style={{ marginTop: 14 }}>
            {(d.hitDetectors ?? []).length === 0 ? (
              <Text type="secondary">暂无命中明细</Text>
            ) : (
              d.hitDetectors.map((hit) => (
                <div key={hit.name} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                  <Tag color={hit.matched ? 'error' : 'default'}>{hit.matched ? '命中' : '未命中'}</Tag>
                  <span style={{ flex: 1 }}>{hit.name}</span>
                  <Text type="secondary">风险 {hit.risk}</Text>
                </div>
              ))
            )}
          </Card>

          {(d.evidence?.memory || d.evidence?.behavior) && (
            <Card title="检测证据" size="small" style={{ marginTop: 14 }}>
              {d.evidence?.memory && (
                <div style={{ marginBottom: 12 }}>
                  <Text strong>内存特征</Text>
                  <div style={{ fontFamily: 'monospace', fontSize: 12, background: 'rgba(255,255,255,.03)', padding: 8, borderRadius: 6, marginTop: 4 }}>{d.evidence.memory}</div>
                </div>
              )}
              {d.evidence?.behavior && (
                <div>
                  <Text strong>行为特征</Text>
                  <div style={{ fontFamily: 'monospace', fontSize: 12, background: 'rgba(255,255,255,.03)', padding: 8, borderRadius: 6, marginTop: 4 }}>{d.evidence.behavior}</div>
                </div>
              )}
              {(d.evidence?.processSnapshot ?? []).length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <Text strong>进程快照</Text>
                  {(d.evidence?.processSnapshot ?? []).map((p, i) => (
                    <div key={i} style={{ fontFamily: 'monospace', fontSize: 12, marginTop: 2 }}>{p}</div>
                  ))}
                </div>
              )}
            </Card>
          )}
        </Col>

        <Col xs={24} lg={9}>
          <Card title="事件时间线" size="small">
            <Steps
              direction="vertical"
              size="small"
              current={d.timeline.length}
              items={d.timeline.map((t) => ({
                title: t.action,
                description: `${fmt(t.at)}${t.note ? ` · ${t.note}` : ''}`,
              }))}
            />
          </Card>

          {d.player && (
            <Card title="玩家信息" size="small" style={{ marginTop: 14 }}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="PTEID">{d.player.pteid}</Descriptions.Item>
                <Descriptions.Item label="信誉分">{d.player.reputation}</Descriptions.Item>
                <Descriptions.Item label="设备">{d.player.device || '-'}</Descriptions.Item>
              </Descriptions>
            </Card>
          )}

          <Card title="操作" size="small" style={{ marginTop: 14 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Button type="primary" block onClick={() => message.success('已发起查端（后端就绪后生效）')}>发起查端</Button>
              <Button block danger onClick={() => message.success('已标记为误报（后端就绪后写入）')}>标记误报</Button>
              <Button block onClick={() => message.success('已提取特征入库（后端就绪后写入）')}>提取特征入库</Button>
              <Button block onClick={() => message.success('已导出证据包（后端就绪后生成）')}>导出证据包</Button>
            </Space>
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