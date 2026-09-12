import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, List, Select, Tag, Typography, message } from 'antd'
import type { FormProps } from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type { Appeal } from '../../types'

const { Title, Text } = Typography

const reasons = [
  { value: '误报申诉', label: '误报申诉' },
  { value: '红屏申诉', label: '红屏申诉' },
  { value: '查端结果异议', label: '查端结果异议' },
  { value: '其他', label: '其他' },
]

type Values = { reason: string; alertId?: string; description?: string }

function statusTag(s: string) {
  const map: Record<string, { color: string; text: string }> = {
    pending: { color: 'warning', text: '待审核' },
    approved: { color: 'success', text: '已通过' },
    rejected: { color: 'error', text: '已驳回' },
  }
  const m = map[s]
  return <Tag color={m?.color}>{m?.text ?? s}</Tag>
}

export default function PlayerAppeals() {
  const [form] = Form.useForm<Values>()
  const [appeals, setAppeals] = useState<Appeal[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const navigate = useNavigate()

  async function load() {
    setLoading(true)
    try {
      setAppeals(await api.player.appeals())
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [])

  const onFinish: FormProps<Values>['onFinish'] = async (v) => {
    setSubmitting(true)
    try {
      await api.player.submitAppeal({
        reason: v.reason,
        alert_id: (v.alertId ?? '').trim(),
        description: v.description ?? '',
      })
      message.success('已提交申诉，等待审核')
      form.resetFields()
      load()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>在线申诉</Title>
        <div style={{ flex: 1 }} />
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable />}
      </div>

      <Card title="提交申诉" style={{ marginBottom: 16 }} styles={{ body: { padding: 20 } }}>
        <Form<Values>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ reason: '误报申诉' }}
          requiredMark={false}
        >
          <Form.Item name="reason" label="申诉类型" rules={[{ required: true }]}>
            <Select options={reasons} placeholder="选择申诉类型" />
          </Form.Item>
          <Form.Item name="alertId" label="关联告警 ID">
            <Input placeholder="可选" maxLength={64} />
          </Form.Item>
          <Form.Item name="description" label="详细说明" rules={[{ max: 2000, message: '描述过长' }]}>
            <Input.TextArea rows={4} showCount maxLength={2000} placeholder="可选 · 请提供相关证据或说明" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>
              提交申诉
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Card title="我的申诉" styles={{ body: { padding: 0 } }}>
        <List
          loading={loading}
          locale={{ emptyText: '暂无申诉记录' }}
          dataSource={appeals}
          renderItem={(a) => (
            <List.Item
              key={a.appealId}
              style={{ padding: '14px 20px', cursor: 'pointer' }}
              onClick={() => navigate(`/portal/appeals/${a.appealId}`)}
              actions={[
                <Button key="detail" type="link" size="small" onClick={(e) => { e.stopPropagation(); navigate(`/portal/appeals/${a.appealId}`) }}>
                  详情
                </Button>,
                <Text type="secondary" key="t" style={{ fontSize: 12 }}>{fmt(a.createdAt)}</Text>,
              ]}
            >
              <List.Item.Meta
                title={
                  <span>
                    {a.reason}
                    <span style={{ marginLeft: 12 }}>{statusTag(a.status)}</span>
                  </span>
                }
                description={
                  <div>
                    <Text type="secondary">{a.description}</Text>
                    {a.reviewComment ? <div style={{ color: 'var(--kpi-green)', fontSize: 13, marginTop: 6 }}>备注：{a.reviewComment}</div> : null}
                  </div>
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}