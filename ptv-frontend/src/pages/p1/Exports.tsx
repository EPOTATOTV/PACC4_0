import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Select, Table, Tag, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ExportTaskRow } from '../../types'

const SUBJECTS = [
  { label: '账号', value: 'accounts' },
  { label: '红屏', value: 'redscreens' },
  { label: '作弊记录', value: 'cheat_records' },
  { label: '申诉', value: 'appeals' },
]

const STATUS_COLOR: Record<string, string> = {
  QUEUED: 'default', RUNNING: 'processing', READY: 'green', FAILED: 'red', EXPIRED: 'default',
}

export default function Exports() {
  const [rows, setRows] = useState<ExportTaskRow[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.p1.exports()
      setRows(d.tasks ?? [])
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function submit() {
    const v = await form.validateFields()
    setSubmitting(true)
    try {
      const r = await api.p1.submitExport({ subject: v.subject, filters: v.filters ?? '' })
      message.success(`已提交导出 #${r.task_id.slice(0, 8)}`)
      form.resetFields()
      void load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  function download(r: ExportTaskRow) {
    if (!r.downloadKey) {
      message.warning('下载凭证已失效，请重新导出')
      return
    }
    window.location.href = `/api/admin/export/${r.id}/download?key=${encodeURIComponent(r.downloadKey)}`
  }

  const columns: TableColumnsType<ExportTaskRow> = [
    { title: '主题', dataIndex: 'subject', width: 120, render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <Tag color={STATUS_COLOR[v] ?? 'default'}>{v}</Tag> },
    { title: '行数', dataIndex: 'rowCount', width: 90 },
    { title: '筛选', dataIndex: 'filters', render: (v?: string) => v || <span style={{ color: 'var(--muted)' }}>—</span> },
    { title: '申请时间', dataIndex: 'requestedAt', width: 170, render: (v: string) => (v ? new Date(v).toLocaleString() : '—') },
    {
      title: '操作', width: 110,
      render: (_, r) =>
        r.status === 'READY' && r.downloadKey ? (
          <Button size="small" type="primary" icon={<DownloadOutlined />} onClick={() => download(r)}>下载</Button>
        ) : r.status === 'FAILED' ? (
          <Tag color="red">失败</Tag>
        ) : (<Tag>处理中</Tag>),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>数据导出中心</h3>
        <div style={{ flex: 1 }} />
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card title="新建导出任务" size="small" style={{ marginBottom: 16 }}>
        <Form form={form} layout="inline" onFinish={submit}>
          <Form.Item label="主题" name="subject" rules={[{ required: true }]} initialValue="accounts">
            <Select options={SUBJECTS} style={{ width: 180 }} />
          </Form.Item>
          <Form.Item label="筛选（JSON）" name="filters">
            <Input placeholder='如 {"status":"PUBLISHED"}' style={{ width: 240 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={submitting}>提交导出</Button>
          </Form.Item>
        </Form>
      </Card>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<ExportTaskRow> rowKey="id" columns={columns} dataSource={rows} loading={loading} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: '暂无导出任务' }} />
      </Card>
    </div>
  )
}