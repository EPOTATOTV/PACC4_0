import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Segmented, Select, Switch, Table, Tag, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ListEntryRow } from '../../types'

const LIST_TYPES = ['BLACK', 'WHITE']
const ENTRY_TYPES = ['PLAYER', 'DEVICE', 'IP', 'PROCESS', 'FEATURE']

export default function Lists() {
  const [rows, setRows] = useState<ListEntryRow[]>([])
  const [listTypes, setListTypes] = useState<string[]>(LIST_TYPES)
  const [entryTypes, setEntryTypes] = useState<string[]>(ENTRY_TYPES)
  const [err, setErr] = useState('')
  const [filter, setFilter] = useState('all')
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    try {
      const d = await api.p1.lists()
      setRows(d.entries ?? [])
      setListTypes(d.list_types ?? LIST_TYPES)
      setEntryTypes(d.entry_types ?? ENTRY_TYPES)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const visible = rows.filter((r) => filter === 'all' || r.list_type === filter)

  async function submit() {
    const v = await form.validateFields()
    try {
      await api.p1.addListEntry({
        list_type: v.list_type,
        entry_type: v.entry_type,
        value: v.value.trim(),
        reason: v.reason ?? '',
        status: v.status ? 'ACTIVE' : 'INACTIVE',
      })
      message.success('已添加名单')
      setOpen(false)
      form.resetFields()
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function remove(id: string) {
    try {
      await api.p1.removeListEntry(id)
      message.success('已移除')
      void load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: TableColumnsType<ListEntryRow> = [
    { title: '方向', dataIndex: 'list_type', width: 90, render: (v: string) => (v === 'BLACK' ? <Tag color="red">黑名单</Tag> : <Tag color="green">白名单</Tag>) },
    { title: '类型', dataIndex: 'entry_type', width: 100, render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: '值', dataIndex: 'value', width: 200 },
    { title: '原因', dataIndex: 'reason', render: (v?: string) => v || <span style={{ color: 'var(--muted)' }}>—</span> },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => (v === 'ACTIVE' ? <Tag color="green">生效</Tag> : <Tag>停用</Tag>) },
    {
      title: '操作', width: 90,
      render: (_, r) => (
        <Popconfirm title="移除该条目？" onConfirm={() => remove(r.id)}>
          <Button size="small" danger>移除</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>黑白名单</h3>
        <div style={{ flex: 1 }} />
        <Segmented
          value={filter}
          onChange={(v) => setFilter(v as string)}
          options={[
            { label: '全部', value: 'all' },
            { label: '黑名单', value: 'BLACK' },
            { label: '白名单', value: 'WHITE' },
          ]}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新增条目</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<ListEntryRow> rowKey="id" columns={columns} dataSource={visible} pagination={false} scroll={{ x: 860 }} locale={{ emptyText: '暂无名单条目' }} />
      </Card>

      <Modal title="新增名单条目" open={open} onCancel={() => setOpen(false)} onOk={submit} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item label="名单方向" name="list_type" rules={[{ required: true }]}>
            <Select options={listTypes.map((t) => ({ label: t, value: t }))} />
          </Form.Item>
          <Form.Item label="对象类型" name="entry_type" rules={[{ required: true }]}>
            <Select options={entryTypes.map((t) => ({ label: t, value: t }))} />
          </Form.Item>
          <Form.Item label="值" name="value" rules={[{ required: true }]}>
            <Input placeholder="PTEID / 设备指纹 / IP / 进程名 / 特征键" />
          </Form.Item>
          <Form.Item label="原因" name="reason">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="立即生效" name="status" valuePropName="checked" initialValue={true}>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}