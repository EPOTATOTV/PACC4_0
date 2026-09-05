import { Button, Form, Input, Select, Typography } from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { ticketChannels } from './meta'

const { Text } = Typography

export interface TicketFormValues {
  channel: string
  subject: string
  body?: string
}

/**
 * 工单提交表单：管理端与玩家端共用。
 * 渠道常量来自 meta.ts，避免各处重复定义。
 */
export default function TicketForm({
  onSubmit,
  submitting,
  submitLabel = '提交工单',
  hint,
}: {
  onSubmit: (v: TicketFormValues) => void
  submitting: boolean
  submitLabel?: string
  hint?: string
}) {
  return (
    <Form<TicketFormValues>
      layout="vertical"
      onFinish={onSubmit}
      initialValues={{ channel: 'ticket' }}
      requiredMark={false}
    >
      <Form.Item name="channel" label="联系渠道" rules={[{ required: true }]}>
        <Select options={ticketChannels} placeholder="选择提交渠道" />
      </Form.Item>
      <Form.Item
        name="subject"
        label="主题"
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
        {hint && <Text type="secondary" style={{ marginRight: 12 }}>{hint}</Text>}
        <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={submitting}>
          {submitLabel}
        </Button>
      </Form.Item>
    </Form>
  )
}
