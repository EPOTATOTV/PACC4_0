import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Form, Input, Modal, Row, Select, Statistic, Switch, Table, Tabs, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'

const { Title, Text } = Typography

// 自动化运维：原生 fetch 直连 /api/admin/ops/**，凭据走同源 HttpOnly cookie（与管理端登录一致）
async function opsFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (init.body) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/api/admin/ops${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

function fmtTime(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? '-' : d.toLocaleString('zh-CN', { hour12: false })
}

function fmtVal(v: unknown): string {
  if (v === null || v === undefined) return '-'
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : (+v).toFixed(2)
  return String(v)
}

const catColor: Record<string, string> = {
  DETECTION: 'blue',
  SCAN: 'purple',
  REDSCREEN: 'volcano',
  THROTTLE: 'gold',
} as const

interface Health {
  status?: string
  db?: string
  db_error?: string
  memory?: { used_mb?: number; max_mb?: number }
  uptime_seconds?: number
}
interface Overview {
  total_crashes?: number
  crash_count_last_24h?: number
  avg_cpu?: number
  avg_mem_mb?: number
  sample_telemetry_count?: number
}
interface CrashRow {
  id: string
  platform?: string
  clientVersion?: string
  os?: string
  arch?: string
  controller?: string
  createdAt?: string
}
interface TelemetryRow {
  id: string
  cpuPercent?: number
  memMb?: number
  detectionLatencyMs?: number
  fpsImpactPercent?: number
  pteid?: string
  createdAt?: string
}
interface ConfigRow {
  id: string
  category?: string
  intValue?: number | null
  doubleValue?: number | null
  boolValue?: boolean | null
  updatedBy?: string | null
  updatedAt?: string
}

export default function OpsCenter() {
  const [health, setHealth] = useState<Health | null>(null)
  const [overview, setOverview] = useState<Overview | null>(null)
  const [crashes, setCrashes] = useState<CrashRow[]>([])
  const [telemetry, setTelemetry] = useState<TelemetryRow[]>([])
  const [configs, setConfigs] = useState<ConfigRow[]>([])
  const [err, setErr] = useState('')
  const [activeTab, setActiveTab] = useState('crashes')

  // 远程配置新增/编辑
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ConfigRow | null>(null)
  const [form] = Form.useForm()

  const loadAll = () => {
    opsFetch<Health>('/health').then(setHealth).catch((e) => setErr((e as Error).message))
    opsFetch<Overview>('/overview').then(setOverview).catch(() => setOverview(null))
    opsFetch<{ crashes: CrashRow[] }>('/crashes?limit=50')
      .then((d) => setCrashes(d.crashes ?? []))
      .catch(() => setCrashes([]))
    opsFetch<{ telemetry: TelemetryRow[] }>('/telemetry?limit=50')
      .then((d) => setTelemetry(d.telemetry ?? []))
      .catch(() => setTelemetry([]))
    loadConfigs()
  }

  const loadConfigs = () => {
    opsFetch<{ configs: ConfigRow[] }>('/config')
      .then((d) => setConfigs(d.configs ?? []))
      .catch(() => setConfigs([]))
  }

  useEffect(() => {
    loadAll()
  }, [])

  const dbUp = health?.db === 'UP'
  const mem = health?.memory

  function openNew() {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }
  function openEdit(row: ConfigRow) {
    setEditing(row)
    const nonNull = row.intValue != null ? 'int' : row.doubleValue != null ? 'double' : 'bool'
    form.resetFields()
    form.setFieldsValue({
      key: row.id,
      category: row.category,
      valueType: nonNull,
      intValue: row.intValue ?? 1,
      doubleValue: row.doubleValue,
      boolValue: row.boolValue ?? false,
    })
    setModalOpen(true)
  }

  async function saveConfig() {
    const v = await form.validateFields()
    const body: Record<string, unknown> = {
      category: v.category,
      updated_by: v.updatedBy || 'admin',
    }
    if (v.valueType === 'int') body.int_value = v.intValue
    else if (v.valueType === 'double') body.double_value = v.doubleValue
    else body.bool_value = v.boolValue
    try {
      await opsFetch(`/config/${encodeURIComponent(v.key)}`, { method: 'PUT', body: JSON.stringify(body) })
      message.success('配置已保存')
      setModalOpen(false)
      loadConfigs()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const crashColumns: TableColumnsType<CrashRow> = [
    {
      title: '时间', key: 'createdAt', width: 170,
      render: (_, r) => fmtTime(r.createdAt),
    },
    {
      title: '平台', key: 'platform', width: 90,
      render: (_, r) => <Tag color="default">{r.platform || 'WINDOWS'}</Tag>,
    },
    { title: '客户端', dataIndex: 'clientVersion', width: 90 },
    { title: 'OS', dataIndex: 'os', width: 130 },
    { title: '架构', dataIndex: 'arch', width: 90 },
    {
      title: '相关检测器', dataIndex: 'controller', ellipsis: true,
      render: (v?: string) => (v ? <Text type="secondary">{v}</Text> : '-'),
    },
  ]

  const telemetryColumns: TableColumnsType<TelemetryRow> = [
    {
      title: '时间', key: 'createdAt', width: 170,
      render: (_, r) => fmtTime(r.createdAt),
    },
    {
      title: 'CPU %', dataIndex: 'cpuPercent', width: 90,
      render: (v?: number) => (v == null ? '-' : <Text strong>{+v.toFixed(2)}%</Text>),
    },
    {
      title: '内存 MB', dataIndex: 'memMb', width: 100,
      render: (v?: number) => (v == null ? '-' : `${v} MB`),
    },
    {
      title: '检测延迟 ms', dataIndex: 'detectionLatencyMs', width: 120,
      render: (v?: number) => (v == null ? '-' : `${v} ms`),
    },
    {
      title: '帧率影响 %', dataIndex: 'fpsImpactPercent', width: 110,
      render: (v?: number) => (v == null ? '-' : `${v} %`),
    },
    { title: 'PTEID', dataIndex: 'pteid', ellipsis: true, render: (v?: string) => v || '-' },
  ]

  const configColumns: TableColumnsType<ConfigRow> = [
    { title: 'Key', dataIndex: 'id', width: 200, render: (v: string) => <code>{v}</code> },
    {
      title: '分类', dataIndex: 'category', width: 110,
      render: (v?: string) => (v ? <Tag color={(catColor[v] ?? 'default') as string}>{v}</Tag> : '-'),
    },
    { title: '值', key: 'value', render: (_, r) => fmtVal(r.intValue ?? r.doubleValue ?? r.boolValue) },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v?: string) => fmtTime(v) },
    {
      title: '操作', key: 'actions', width: 90,
      render: (_, r) => <Button size="small" onClick={() => openEdit(r)}>编辑</Button>,
    },
  ]

  return (
    <div style={{ maxWidth: 1180 }}>
      <Title level={3} style={{ marginTop: 0 }}>自动化运维 · v4.7</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        服务健康检查 · 客户端崩溃/性能上报 · 远程配置下发
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="服务状态" value={health?.status ?? '-'} valueStyle={{ color: dbUp ? '#3fb950' : '#f85149' }} />
            <div style={{ marginTop: 4 }}>
              DB：<Tag color={dbUp ? 'success' : 'error'}>{health?.db ?? '-'}</Tag>
              {health?.db_error && <Text type="secondary" style={{ fontSize: 12 }}>{health.db_error}</Text>}
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="JVM 内存"
              value={mem ? `${mem.used_mb ?? 0} / ${mem.max_mb ?? 0}` : '-'}
              suffix="MB"
            />
            <div style={{ marginTop: 4 }}><Text type="secondary" style={{ fontSize: 12 }}>已用 / 上限</Text></div>
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="运行时长"
              value={health?.uptime_seconds ? `${Math.floor(health.uptime_seconds / 86400)}天 ${Math.floor((health.uptime_seconds % 86400) / 3600)}h` : '-'}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="近24h崩溃" value={overview ? (overview.crash_count_last_24h ?? 0) : '-'} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card><Statistic title="平均 CPU %" value={overview?.avg_cpu ?? '-'} precision={overview?.avg_cpu != null ? 2 : undefined} /></Card>
        </Col>
        <Col span={8}>
          <Card><Statistic title="平均内存 MB" value={overview?.avg_mem_mb ?? '-'} precision={overview?.avg_mem_mb != null ? 1 : undefined} /></Card>
        </Col>
        <Col span={8}>
          <Card><Statistic title="性能采样 / 崩溃总数" value={`${overview?.sample_telemetry_count ?? '-'} / ${overview?.total_crashes ?? '-'}`} /></Card>
        </Col>
      </Row>

      <Card style={{ marginBottom: 16 }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'crashes',
              label: `崩溃上报（${crashes.length}）`,
              children: (
                <Table<CrashRow>
                  rowKey="id"
                  columns={crashColumns}
                  dataSource={crashes}
                  size="small"
                  pagination={{ pageSize: 10, hideOnSinglePage: true }}
                  locale={{ emptyText: '暂无崩溃上报' }}
                  scroll={{ x: 760 }}
                />
              ),
            },
            {
              key: 'telemetry',
              label: `性能上报（${telemetry.length}）`,
              children: (
                <Table<TelemetryRow>
                  rowKey="id"
                  columns={telemetryColumns}
                  dataSource={telemetry}
                  size="small"
                  pagination={{ pageSize: 10, hideOnSinglePage: true }}
                  locale={{ emptyText: '暂无性能上报' }}
                  scroll={{ x: 760 }}
                />
              ),
            },
          ]}
        />
      </Card>

      <Card
        title="远程配置"
        extra={<Button type="primary" size="small" onClick={openNew}>新增</Button>}
        style={{ border: '1px solid var(--border-strong)' }}
      >
        <Table<ConfigRow>
          rowKey="id"
          columns={configColumns}
          dataSource={configs}
          size="small"
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '暂无配置' }}
          scroll={{ x: 720 }}
        />
      </Card>

      <Modal
        title={editing ? '编辑配置' : '新增配置'}
        open={modalOpen}
        onOk={saveConfig}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ valueType: 'int', intValue: 1, category: 'DETECTION' }}>
          <Form.Item name="key" label="Key" rules={[{ required: true, message: '请输入配置 Key' }]}>
            <Input placeholder="如 scan.killaura_interval" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="category" label="分类" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'DETECTION', label: 'DETECTION - 检测' },
                { value: 'SCAN', label: 'SCAN - 扫描' },
                { value: 'REDSCREEN', label: 'REDSCREEN - 红屏' },
                { value: 'THROTTLE', label: 'THROTTLE - 节流' },
              ]}
            />
          </Form.Item>
          <Form.Item name="valueType" label="值类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'int', label: '整数' },
                { value: 'double', label: '小数' },
                { value: 'bool', label: '布尔' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(a, b) => a.valueType !== b.valueType}>
            {({ getFieldValue }) =>
              getFieldValue('valueType') === 'int' ? (
                <Form.Item name="intValue" label="整数值" rules={[{ required: true }]}>
                  <Input type="number" placeholder="默认 1" />
                </Form.Item>
              ) : getFieldValue('valueType') === 'double' ? (
                <Form.Item name="doubleValue" label="小数值" rules={[{ required: true }]}>
                  <Input type="number" step="0.01" />
                </Form.Item>
              ) : (
                <Form.Item name="boolValue" label="布尔值" valuePropName="checked">
                  <Switch />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="updatedBy" label="更新人">
            <Input placeholder="默认 admin" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}