import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, List, Select, Tag, Typography, message } from 'antd'
import type { FormProps } from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { SupportTicket } from '../../types'

const { Title, Text } = Typography

const channels = [
  { value: 'ticket', label: '站内工单' },
  { value: 'email', label: '邮件' },
  { value: 'qq', label: 'QQ' },
  { value: 'discord', label: 'Discord' },
]

type Values = { channel: string; subject: string; body?: string }

function statusTag(s: string) {
  const map: Record<string, { color: string; text: string }> = {
    open: { color: 'processing', text: '等待处理' },
    in_progress: { color: 'warning', text: '处理中' },
    resolved: { color: 'success', text: '已解决' },
    closed: { color: 'default', text: '已关闭' },
  }
  const m = map[s]
  return <Tag color={m?.color}>{m?.text ?? s}</Tag>
}

export default function PlayerTickets() {
  const [form] = Form.useForm<Values>()
  const [tickets, setTickets] = useState<SupportTicket[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  async function load() {
    setLoading(true)
    try {
      setTickets(await api.player.tickets())
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [])

  const onFinish: FormProps<Values>['onFinish'] = async (v) => {
    if (!v.subject || !v.subject.trim()) return
    setSubmitting(true)
    try {
      await api.player.submitTicket({ channel: v.channel, subject: v.subject, body: v.body ?? '' })
      message.success('工单已提交，客服将尽快处理')
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
      <Title level={3} style={{ marginTop: 0 }}>客服工单</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Card title="提交工单" style={{ marginBottom: 16 }} styles={{ body: { padding: 20 } }}>
        <Form<Values>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ channel: 'ticket' }}
          requiredMark={false}
        >
          <Form.Item name="channel" label="联系渠道" rules={[{ required: true }]}>
            <Select options={channels} placeholder="选择提交渠道" />
          </Form.Item>
          <Form.Item
            name="subject" label="主题"
            rules={[{ required: true, whitespace: true, message: '请填写工单主题' }]}
          >
            <Input maxLength={120} showCount placeholder="简要描述问题" />
          </Form.Item>
          <Form.Item name="body" label="问题描述" rules={[{ max: 2000, message: '描述过长' }]}>
            <Input.TextArea
              rows={4}
              showCount
              maxLength={2000}
              placeholder="请提供尽量详细的信息，如时间、涉及账号、复现步骤等"
            />
          </Form.Item>
          <Form.Item className="tag" style={{ marginBottom: 0, textAlign: 'right' }}>
            <Text type="secondary" style={{ marginRight: 12 }}>工单会在后台按渠道分类统一处理</Text>
            <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>
              提交工单
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Card title="我的工单" styles={{ body: { padding: 0 } }}>
        <List
          loading={loading}
          locale={{ emptyText: '暂无工单' }}
          dataSource={tickets}
          renderItem={(t) => (
            <List.Item
              key={t.ticketId}
              style={{ padding: '14px 20px' }}
              actions={[<Text type="secondary" key="a" style={{ fontSize: 12 }}>{fmt(t.createdAt)}</Text>]}
            >
              <List.Item.Meta
                title={
                  <span>
                    {t.subject}
                    <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                      ({t.channel})
                    </Text>
                    <span style={{ marginLeft: 12 }}>{statusTag(t.status)}</span>
                  </span>
                }
                description={
                  <div>
                    <Text type="secondary">{t.body}</Text>
                    {t.resolution ? <div style={{ color: '#3fb950', fontSize: 13, marginTop: 6 }}>处理结果：{t.resolution}</div> : null}
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