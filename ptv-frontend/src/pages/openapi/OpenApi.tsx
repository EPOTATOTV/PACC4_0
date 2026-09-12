import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../../api/client'
import type { OpenApiAuditRow } from '../../types'

const { Title, Text, Paragraph } = Typography

interface ApiKeyRow {
  keyId: string
  name: string
  tenantId: string
  plan: string
  scopes: string
  categories?: string
  ipWhitelist?: string
  rateLimitPerHour: number
  webhookUrl?: string
  enabled: boolean
  createdAt: string
  lastUsedAt?: string
}

const planColor: Record<string, string> = { FREE: 'default', PRO: 'blue', ENTERPRISE: 'purple' }

function fmtTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

export default function OpenApi() {
  const [keys, setKeys] = useState<ApiKeyRow[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [result, setResult] = useState<{ keyId: string; secret: string } | null>(null)
  const [audit, setAudit] = useState<{ rows: OpenApiAuditRow[]; total: number } | null>(null)

  const reload = () => {
    setLoading(true)
    api.openApi
      .keys()
      .then(setKeys)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false))
  }

  const reloadAudit = () => {
    api.openApi
      .audit()
      .then(setAudit)
      .catch(() => {})
  }

  useEffect(() => {
    reload()
    reloadAudit()
  }, [])

  const onCreate = async (v: Record<string, unknown>) => {
    setCreating(false)
    const name = (v.name as string) || '未命名密钥'
    const body: Record<string, string | number> = { name, plan: String(v.plan), scopes: String(v.scopes) }
    if (v.categories) body.categories = v.categories as string
    if (v.ipWhitelist) body.ipWhitelist = v.ipWhitelist as string
    if (v.rateLimit) body.rateLimit = Number(v.rateLimit)
    if (v.webhookUrl) body.webhookUrl = v.webhookUrl as string
    if (v.webhookSecret) body.webhookSecret = v.webhookSecret as string
    try {
      const r = await api.openApi.createKey(body)
      setResult({ keyId: r.keyId, secret: r.secret })
      reload()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  const columns: TableColumnsType<ApiKeyRow> = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: 'Key ID', dataIndex: 'keyId', key: 'keyId', render: (k: string) => <Text code>{k}</Text> },
    {
      title: '套餐', dataIndex: 'plan', key: 'plan', width: 100,
      render: (p: string) => <Tag color={planColor[p] ?? 'default'}>{p}</Tag>,
    },
    {
      title: '范围', dataIndex: 'scopes', key: 'scopes', width: 110,
      render: (s: string) => (s.toUpperCase().includes('WRITE') ? <Tag color="orange">读写</Tag> : <Tag>只读</Tag>),
    },
    { title: '限流/时', dataIndex: 'rateLimitPerHour', key: 'limit', width: 90 },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 150, render: fmtTime },
    {
      title: '启用', key: 'enabled', width: 70,
      render: (_: unknown, r: ApiKeyRow) => (
        <Switch
          size="small"
          checked={r.enabled}
          onChange={(checked) => api.openApi.toggleKey(r.keyId, checked).then(reload)}
        />
      ),
    },
    {
      title: '操作', key: 'op', width: 180,
      render: (_: unknown, r: ApiKeyRow) => (
        <Space size={4}>
          <Button
            size="small" type="link"
            onClick={() => Modal.confirm({
              title: '轮换密钥？',
              content: '旧密钥将立即失效，新密钥只显示一次，请妥善保存。',
              onOk: async () => {
                const rr = await api.openApi.rotateKey(r.keyId)
                setResult({ keyId: r.keyId, secret: rr.secret })
                reload()
              },
            })}
          >
            轮换
          </Button>
          <Button
            size="small" type="link" danger
            onClick={() => Modal.confirm({
              title: '删除密钥？',
              content: '该密钥对应的所有调用将拒绝。',
              onOk: async () => { await api.openApi.deleteKey(r.keyId); reload() },
            })}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginBottom: 18, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>开放 API · 密钥与调用审计</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          为第三方/租户签发 API 密钥（HMAC-SHA256 签名调用 /api/v1/**），并记录每次调用方/接口/IP/返回码
        </Text>
        <div style={{ flex: 1 }} />
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 18 }} closable onClose={() => setErr('')} />}

      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, alignItems: 'center' }}>
        <span style={{ fontWeight: 600 }}>API 密钥</span>
        <Button type="primary" size="small" onClick={() => setCreating(true)}>新建密钥</Button>
      </div>
      <Card size="small" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none', marginBottom: 20 }} styles={{ body: { padding: 0 } }}>
        <Table rowKey="keyId" size="small" loading={loading} dataSource={keys} columns={columns}
          pagination={false} locale={{ emptyText: '暂无 API 密钥，点击右上角新建' }} />
      </Card>

      <div style={{ borderBottom: '1px solid var(--border-strong)', marginBottom: 12, paddingBottom: 6 }}>
        <span style={{ fontWeight: 600 }}>最近调用（审计）</span>
      </div>
      <Card size="small" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none' }} styles={{ body: { padding: 0 } }}>
        <Table
          rowKey="id" size="small" dataSource={audit?.rows ?? []}
          pagination={{ pageSize: 8, total: audit?.total ?? 0, showSizeChanger: false }}
          locale={{ emptyText: '暂无调用记录' }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', key: 'createdAt', render: fmtTime, width: 150 },
            { title: '方法', dataIndex: 'method', key: 'method', width: 80 },
            { title: '接口', dataIndex: 'path', key: 'path' },
            { title: 'Key', dataIndex: 'apiKeyId', key: 'apiKeyId' },
            { title: 'IP', dataIndex: 'ip', key: 'ip', width: 130 },
            {
              title: '状态', dataIndex: 'statusCode', key: 'statusCode', width: 80,
              render: (c: number) => <Tag color={c < 400 ? 'success' : 'error'}>{c}</Tag>,
            },
            { title: '耗时(ms)', dataIndex: 'latencyMs', key: 'latencyMs', width: 90, render: (v: number) => v ?? '-' },
          ]}
        />
      </Card>

      <Modal title="新建 API 密钥" open={creating} onCancel={() => setCreating(false)} footer={null} width={460}>
        <Form layout="vertical" onFinish={onCreate} initialValues={{ plan: 'PRO', scopes: 'READ' }}>
          <Form.Item name="name" label="名称">
            <Input placeholder="如：第三方运营接入" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="plan" label="套餐" style={{ flex: 1 }}>
              <Select options={[{ value: 'FREE', label: '免费版 100/时' }, { value: 'PRO', label: '专业版 1000/时' }, { value: 'ENTERPRISE', label: '企业版 10000/时' }]} />
            </Form.Item>
            <Form.Item name="scopes" label="权限范围" style={{ flex: 1 }}>
              <Select options={[{ value: 'READ', label: '只读' }, { value: 'READ,WRITE', label: '读写' }]} />
            </Form.Item>
          </Space>
          <Form.Item name="categories" label="可用 API 类别（逗号分隔，留空=全部）">
            <Input placeholder="detections,redscreen,players,policy,signatures,stats" />
          </Form.Item>
          <Form.Item name="ipWhitelist" label="IP 白名单（逗号分隔，留空=不限）">
            <Input placeholder="1.2.3.4, 203.0.113.0" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="rateLimit" label="限流/小时（留空按套餐）" style={{ flex: 1 }}>
              <Input type="number" placeholder="1000" />
            </Form.Item>
          </Space>
          <Form.Item name="webhookUrl" label="Webhook 回调地址（可选）">
            <Input placeholder="https://partner.example.com/ptv/webhook" />
          </Form.Item>
          <Form.Item name="webhookSecret" label="Webhook 签名密钥（可选）">
            <Input placeholder="用于回调包 HMAC 验签" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>创建</Button>
        </Form>
      </Modal>

      <Modal
        title="密钥已生成，请立即保存"
        open={!!result}
        onCancel={() => setResult(null)}
        footer={<Button type="primary" onClick={() => setResult(null)}>我已保存</Button>}
      >
        <Alert type="warning" showIcon message="密钥只显示这一次，关闭后无法再次查看；落库为加密存储。" style={{ marginBottom: 14 }} />
        <Paragraph><Text type="secondary">Key ID</Text></Paragraph>
        <Paragraph copyable style={{ marginBottom: 10 }}><Text code>{result?.keyId}</Text></Paragraph>
        <Paragraph><Text type="secondary">Secret（用于 HMAC 签名）</Text></Paragraph>
        <Paragraph copyable style={{ wordBreak: 'break-all', marginBottom: 0 }}><Text code>{result?.secret}</Text></Paragraph>
      </Modal>
    </div>
  )
}