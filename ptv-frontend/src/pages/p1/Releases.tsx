import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Segmented, Select,
  Space, Table, Tag, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ReleaseInfoRow } from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

const PLATFORMS = ['windows', 'android', 'ios', 'macos', 'linux']
const CHANNELS = ['stable', 'beta', 'canary']

const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', PUBLISHED: 'green', ARCHIVED: 'default' }

export default function Releases() {
  const [rows, setRows] = useState<ReleaseInfoRow[]>([])
  const [err, setErr] = useState('')
  const [filter, setFilter] = useState('all')
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -18 })
  const revealModal = useModalReveal()

  const load = useCallback(async () => {
    try {
      const d = await api.p1.releases()
      setRows(d.releases ?? [])
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const visible = rows.filter((r) => filter === 'all' || r.status === filter)

  // 数据到达/切换筛选后重播行入场，等新行进 DOM 再触发
  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [visible, reveal])

  // 弹层挂载完成后再接管入场，避免与 antd 默认过渡抢同一组 transform
  useEffect(() => {
    if (!open) return
    const id = requestAnimationFrame(revealModal)
    return () => cancelAnimationFrame(id)
  }, [open, revealModal])

  async function submit() {
    const v = await form.validateFields()
    try {
      const id = await api.p1.createRelease({
        platform: v.platform,
        channel: v.channel,
        version: v.version,
        build_no: v.build_no ?? 0,
        notes: v.notes ?? '',
        download_url: v.download_url ?? '',
        sha256: v.sha256 ?? '',
      })
      message.success(`已创建发布 #${id.slice(0, 8)}`)
      setOpen(false)
      form.resetFields()
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: TableColumnsType<ReleaseInfoRow> = [
    { title: '平台', dataIndex: 'platform', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: '渠道', dataIndex: 'channel', width: 90, render: (v: string) => <Tag color="cyan">{v}</Tag> },
    { title: '版本', dataIndex: 'version', width: 110 },
    { title: 'Build', dataIndex: 'build_no', width: 90 },
    {
      title: '灰度', dataIndex: 'manual_enabled', width: 80,
      render: (v: boolean) => (v ? <Tag color="orange">放量</Tag> : <Tag>否</Tag>),
    },
    {
      title: '强制更新', dataIndex: 'forced_enabled', width: 100,
      render: (v: boolean) => (v ? <Tag color="red">强制</Tag> : <Tag>否</Tag>),
    },
    { title: '崩溃率', dataIndex: 'crash_rate_pct', width: 90, render: (v: number) => `${(v ?? 0).toFixed(2)}%` },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => <Tag color={STATUS_COLOR[v] ?? 'default'}>{v}</Tag> },
    {
      title: '操作', width: 220,
      render: (_, r) => (
        <Space>
          {r.status === 'DRAFT' && (
            <Button size="small" type="primary" onClick={() => publish(r)}>发布</Button>
          )}
          {r.status === 'PUBLISHED' && (
            <Button size="small" onClick={() => archive(r)}>归档</Button>
          )}
          {(r.status === 'DRAFT' || r.status === 'ARCHIVED') && (
            <Popconfirm title="删除该发布记录？" onConfirm={() => remove(r)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  async function publish(r: ReleaseInfoRow) {
    try {
      await api.p1.publishRelease(r.id)
      message.success('已发布')
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }
  async function archive(r: ReleaseInfoRow) {
    try {
      await api.p1.archiveRelease(r.id)
      message.success('已归档')
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }
  async function remove(r: ReleaseInfoRow) {
    try {
      await api.p1.deleteRelease(r.id)
      message.success('已删除')
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>客户端版本发布</h3>
        <div style={{ flex: 1 }} />
        <Segmented
          value={filter}
          onChange={(v) => setFilter(v as string)}
          options={[
            { label: '全部', value: 'all' },
            { label: '草稿', value: 'DRAFT' },
            { label: '已发布', value: 'PUBLISHED' },
            { label: '已归档', value: 'ARCHIVED' },
          ]}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建发布</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card styles={{ body: { padding: 0 } }}>
        <div ref={tableRef}>
          <Table<ReleaseInfoRow> rowKey="id" columns={columns} dataSource={visible} pagination={false} scroll={{ x: 980 }} locale={{ emptyText: '暂无发布记录' }} />
        </div>
      </Card>

      <Modal title="新建版本发布" open={open} onCancel={() => setOpen(false)} onOk={submit} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item label="平台" name="platform" rules={[{ required: true }]}>
            <Select options={PLATFORMS.map((p) => ({ label: p, value: p }))} />
          </Form.Item>
          <Form.Item label="渠道" name="channel" initialValue="stable" rules={[{ required: true }]}>
            <Select options={CHANNELS.map((c) => ({ label: c, value: c }))} />
          </Form.Item>
          <Form.Item label="版本号" name="version" rules={[{ required: true }]}>
            <Input placeholder="如 5.0.0" />
          </Form.Item>
          <Form.Item label="Build No" name="build_no">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="发布说明" name="notes">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="下载地址" name="download_url">
            <Input />
          </Form.Item>
          <Form.Item label="SHA256" name="sha256">
            <Input placeholder="安装包完整性校验" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}