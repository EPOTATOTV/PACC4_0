import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'

const { Title, Text } = Typography

type Tenant = Record<string, unknown>
type TenantAdmin = { id: number; tenantId: string; adminIdentity: string; role: string; enabled: boolean }

const PLAN_COLOR: Record<string, string> = { FREE: 'default', PRO: 'geekblue', ENTERPRISE: 'gold' }

const planOptions = [
  { value: 'FREE', label: '免费版' },
  { value: 'PRO', label: '专业版' },
  { value: 'ENTERPRISE', label: '企业版' },
]

export default function TenantPage() {
  const [rows, setRows] = useState<Tenant[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const [search, setSearch] = useState('')

  const [form] = Form.useForm()
  const [editing, setEditing] = useState<Tenant | null>(null)
  const [open, setOpen] = useState(false)

  const [current, setCurrent] = useState<Tenant | null>(null)
  const [admins, setAdmins] = useState<TenantAdmin[]>([])
  const [addOpen, setAddOpen] = useState(false)
  const [addForm] = Form.useForm()

  const load = useCallback((pg = 0, kw = search) => {
    setLoading(true)
    api.tenant.list({ page: String(pg), size: '20', ...(kw ? { search: kw } : {}) })
      .then((d) => { setRows(d.rows ?? []); setTotal(d.total ?? 0); setPage(d.page ?? pg) })
      .catch((e) => setErr(String(e.message || e)))
      .finally(() => setLoading(false))
  }, [search])

  useEffect(() => { load(0) }, [load])

  const loadAdmins = useCallback((tenant: Tenant) => {
    api.tenant.admins(String(tenant.tenantId)).then(setAdmins).catch(() => setAdmins([]))
  }, [])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ plan: 'FREE', status: 'ACTIVE', max_admins: 1, data_retention_days: 30 })
    setOpen(true)
  }

  const openEdit = (t: Tenant) => {
    setEditing(t)
    form.setFieldsValue({
      name: t.name,
      plan: t.plan,
      status: t.status,
      max_admins: t.maxAdmins,
      data_retention_days: t.dataRetentionDays,
      api_access: t.apiAccess,
      webhook_access: t.webhookAccess,
      custom_policy: t.customPolicy,
      note: t.note,
    })
    setOpen(true)
  }

  const submit = async () => {
    const v = await form.validateFields()
    try {
      if (editing) {
        await api.tenant.update(String(editing.tenant_id), v)
        message.success('已更新租户')
      } else {
        await api.tenant.create({ tenant_id: v.tenant_id, ...v })
        message.success('已创建租户')
      }
      setOpen(false)
      load(page)
    } catch (e) {
      setErr(String((e as Error)?.message || e))
    }
  }

  const remove = async (t: Tenant) => {
    try {
      await api.tenant.remove(String(t.tenant_id))
      message.success('已删除租户')
      load(page)
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const openAdmins = (t: Tenant) => {
    setCurrent(t)
    setAdmins([])
    loadAdmins(t)
  }

  const toggleAdmin = async (a: TenantAdmin, enabled: boolean) => {
    if (!current) return
    try {
      await api.tenant.setAdminEnabled(String(current.tenantId), a.adminIdentity, enabled)
      loadAdmins(current)
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const deleteAdmin = async (a: TenantAdmin) => {
    if (!current) return
    try {
      await api.tenant.removeAdmin(String(current.tenantId), a.adminIdentity)
      loadAdmins(current)
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const submitAdmin = async () => {
    if (!current) return
    const v = await addForm.validateFields()
    try {
      await api.tenant.addAdmin(String(current.tenantId), v.identity, v.role || 'admin')
      message.success('已绑定管理员')
      addForm.resetFields()
      setAddOpen(false)
      loadAdmins(current)
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const cols: TableColumnsType<Tenant> = [
    { title: '租户标识', dataIndex: 'tenantId', width: 160, render: (v) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '名称', dataIndex: 'name', width: 160 },
    { title: '套餐', dataIndex: 'plan', width: 110, render: (v) => <Tag color={PLAN_COLOR[v as string] ?? 'default'}>{String(v).toUpperCase()}</Tag> },
    { title: '状态', dataIndex: 'status', width: 100, render: (v) => (v === 'ACTIVE' ? <Tag color="success">启用</Tag> : <Tag color="error">停用</Tag>) },
    { title: '管理员上限', dataIndex: 'maxAdmins', width: 110 },
    { title: '数据留存(天)', dataIndex: 'dataRetentionDays', width: 120 },
    { title: '功能', width: 210, render: (_, t) => (
      <Space size={4} wrap>
        {t.apiAccess ? <Tag color="blue">API</Tag> : null}
        {t.webhookAccess ? <Tag color="purple">Webhook</Tag> : null}
        {t.customPolicy ? <Tag color="cyan">自定义策略</Tag> : null}
        {!t.apiAccess && !t.webhookAccess && !t.customPolicy ? <Text type="secondary">—</Text> : null}
      </Space>
    ) },
    { title: '创建者', dataIndex: 'createdBy', width: 120, render: (v) => v ?? <Text type="secondary">—</Text> },
    {
      title: '操作', width: 220,
      render: (_, t) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(t)}>编辑</Button>
          <Button size="small" onClick={() => openAdmins(t)}>管理员</Button>
          <Popconfirm title="删除该租户？将同时移除其管理员绑定" onConfirm={() => remove(t)} okText="删除" cancelText="取消">
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>租户管理</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          管理平台租户分级（免费 / 专业 / 企业）与功能矩阵，绑定租户管理员；数据隔离由 API 密钥的租户维度承载
        </Text>
        <div style={{ flex: 1 }} />
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Card style={{ marginTop: 20 }} styles={{ body: { paddingTop: 12 } }}
        title={<Space>
          <Input
            placeholder="按名称 / 租户标识搜索" allowClear value={search} style={{ width: 240 }}
            onChange={(e) => setSearch(e.target.value)} onPressEnter={() => load(0)} />
          <Button onClick={() => load(0)}>查询</Button>
        </Space>}
        extra={<Button type="primary" onClick={openCreate}>新建租户</Button>}>
        <Table<Tenant> rowKey="tenant_id" columns={cols} dataSource={rows} loading={loading} size="middle"
          pagination={{ current: page + 1, pageSize: 20, total, showSizeChanger: false, onChange: (pg) => load(pg - 1) }}
          scroll={{ x: 1080 }} locale={{ emptyText: '暂无租户' }} />
      </Card>

      <Modal title={editing ? `编辑租户 ${editing.tenant_id}` : '新建租户'} open={open} onOk={submit} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {!editing && <Form.Item name="tenant_id" label="租户标识" rules={[{ required: true, message: '请输入租户标识' }]}>
            <Input placeholder="如 acme-corp" />
          </Form.Item>}
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入租户名称' }]}>
            <Input placeholder="如 ACME 电竞俱乐部" />
          </Form.Item>
          <Form.Item name="plan" label="套餐">
            <Select options={planOptions} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 'ACTIVE', label: '启用' }, { value: 'SUSPENDED', label: '停用' }]} />
          </Form.Item>
          <Space size={24} wrap>
            <Form.Item name="max_admins" label="管理员上限"><InputNumber min={1} /></Form.Item>
            <Form.Item name="data_retention_days" label="数据留存天数"><InputNumber min={1} /></Form.Item>
          </Space>
          <Form.Item name="api_access" label="开放 API 访问" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="webhook_access" label="Webhook 推送" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="custom_policy" label="自定义策略" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="note" label="备注"><Input.TextArea rows={2} placeholder="可选" /></Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={current ? `租户管理员 · ${current.name} (${current.tenantId}) · 上限 ${current.maxAdmins}` : '租户管理员'}
        width={520} open={!!current} onClose={() => setCurrent(null)}>
        <Button type="dashed" block onClick={() => setAddOpen(true)} style={{ marginBottom: 16 }}>绑定管理员</Button>
        <Table<TenantAdmin>
          rowKey="id" size="small" dataSource={admins} pagination={false} locale={{ emptyText: '暂无管理员' }}
          columns={[
            { title: '身份', dataIndex: 'adminIdentity', render: (v) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
            { title: '角色', dataIndex: 'role', width: 90, render: (v) => <Tag>{v}</Tag> },
            {
              title: '启用', dataIndex: 'enabled', width: 70,
              render: (v, a) => <Switch size="small" checked={!!v} onChange={(c) => toggleAdmin(a, c)} />,
            },
            {
              title: '操作', width: 70,
              render: (_, a) => <Popconfirm title="解绑该管理员？" onConfirm={() => deleteAdmin(a)}><Button size="small" danger>解绑</Button></Popconfirm>,
            },
          ]} />
      </Drawer>

      <Modal title="绑定租户管理员" open={addOpen} onOk={submitAdmin} onCancel={() => setAddOpen(false)} destroyOnClose>
        <Form form={addForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="identity" label="管理员身份" rules={[{ required: true, message: '请输入管理员身份' }]}>
            <Input placeholder="如 admin@potatotv.asia" />
          </Form.Item>
          <Form.Item name="role" label="角色">
            <Select options={[{ value: 'admin', label: 'admin' }, { value: 'viewer', label: 'viewer' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}