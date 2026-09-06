import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { TableColumnsType } from 'antd'

const { Title, Text } = Typography

/** A/B 实验实体（对应后端 camelCase JSON 字段） */
interface AbExp {
  id: string
  name: string
  description?: string
  dimension: string
  variantA: string
  variantB: string
  targetPercent: number
  status: string
  metricsCtExposure: number
  metricsCtDetect: number
  metricsCtFalsePositive: number
  startedAt?: string
  endedAt?: string
  winner?: string
  createdAt: string
}

/** 显著性结果（后端返回 snake_case） */
interface Sig {
  ctr_a: number
  ctr_b: number
  lift: number
  p_value: number
  significant: boolean
}

const DIMENSIONS = ['THRESHOLD', 'ALGORITHM', 'SCAN', 'SIGNATURE', 'REDSCREEN', 'PERFORMANCE']

const statusColor: Record<string, string> = {
  RUNNING: 'processing',
  FINISHED: 'warning',
  ARCHIVED: 'success',
  DRAFT: 'default',
}

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const res = await fetch(`/api/admin${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  if (res.status === 204) {
    return undefined as unknown as T
  }
  return res.json() as Promise<T>
}

export default function AbExperiment() {
  const [list, setList] = useState<AbExp[]>([])
  const [sigMap, setSigMap] = useState<Record<string, Sig>>({})
  const [err, setErr] = useState('')
  const [form] = Form.useForm()

  // 显著性弹窗
  const [sig, setSig] = useState<Sig | null>(null)
  const [sigExp, setSigExp] = useState<AbExp | null>(null)
  const [sigLoading, setSigLoading] = useState(false)

  async function load() {
    try {
      const listData = await req<AbExp[]>('/api/admin/ab')
      setList(listData)
      setErr('')
      // 数据量小，逐条拉取显著性用于显著标签
      const entries = await Promise.all(
        listData.map(async (e) => {
          try {
            return [e.id, await req<Sig>(`/api/admin/ab/${e.id}/significance`)] as const
          } catch {
            return [e.id, null] as const
          }
        }),
      )
      const m: Record<string, Sig> = {}
      entries.forEach(([id, s]) => {
        if (s) m[id] = s
      })
      setSigMap(m)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function create() {
    try {
      const v = await form.validateFields()
      await req('/api/admin/ab', {
        method: 'POST',
        body: JSON.stringify({
          name: v.name,
          dimension: v.dimension,
          variant_a: v.variant_a,
          variant_b: v.variant_b,
          target_percent: v.target_percent,
          description: v.description,
        }),
      })
      message.success('实验已创建并开始运行')
      form.resetFields()
      load()
    } catch (e) {
      message.error((e as Error).message || '校验失败')
    }
  }

  async function viewSignificance(e: AbExp) {
    setSigExp(e)
    setSigLoading(true)
    setSig(null)
    try {
      setSig(await req<Sig>(`/api/admin/ab/${e.id}/significance`))
    } catch (ex) {
      message.error((ex as Error).message)
    } finally {
      setSigLoading(false)
    }
  }

  async function finish(id: string) {
    try {
      await req(`/api/admin/ab/finish/${id}`, { method: 'POST' })
      message.success('实验已完成')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function publish(id: string) {
    try {
      await req(`/api/admin/ab/publish/${id}`, { method: 'POST' })
      message.success('已采纳发布')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: TableColumnsType<AbExp> = [
    { title: 'ID', dataIndex: 'id', width: 90, render: (v: string) => <Text style={{ fontSize: 12, fontFamily: 'monospace' }}>{v}</Text> },
    { title: '名称', dataIndex: 'name', width: 140 },
    { title: '维度', dataIndex: 'dimension', width: 120 },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string, e) => (
        <Space>
          <Tag color={(statusColor[v] ?? 'default') as string}>{v}</Tag>
          {e.winner && <Tag color="green">采纳:{e.winner}</Tag>}
        </Space>
      ),
    },
    {
      title: 'AB 占比', dataIndex: 'targetPercent', width: 120,
      render: (v: number, e) => `${e.variantB} ${v}% / ${e.variantA} ${100 - v}%`,
    },
    {
      title: '曝光/命中', key: 'metrics', width: 120,
      render: (_, e) => `${e.metricsCtExposure} / ${e.metricsCtDetect}`,
    },
    {
      title: 'CTR 对比', key: 'ctr', width: 130,
      render: (_, e) => {
        const c = sigMap[e.id]
        if (!c) return <Text type="secondary">-</Text>
        return `${(c.ctr_b * 100).toFixed(2)}% vs ${(c.ctr_a * 100).toFixed(2)}%`
      },
    },
    {
      title: '显著', key: 'sig', width: 90,
      render: (_, e) => {
        const c = sigMap[e.id]
        if (!c) return <Text type="secondary">-</Text>
        return c.significant ? <Tag color="green">显著</Tag> : <Tag>不显著</Tag>
      },
    },
    {
      title: '操作', key: 'actions', width: 300,
      render: (_, e) => (
        <Space wrap>
          <Button size="small" onClick={() => viewSignificance(e)}>查看显著性</Button>
          {e.status === 'RUNNING' && (
            <Button size="small" onClick={() => finish(e.id)}>完成实验</Button>
          )}
          {e.status === 'FINISHED' && (
            <Button size="small" type="primary" onClick={() => publish(e.id)}>采纳发布</Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div style={{ maxWidth: 1180 }}>
      <Title level={3} style={{ marginTop: 0 }}>检测算法 A/B 测试</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        v4.7 · 创建实验 → 采集曝光/命中/误报指标 → z 检验显著性判定 → 采纳发布
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Card title="新建实验" style={{ marginBottom: 16 }}>
        <Form form={form} layout="inline" style={{ rowGap: 12 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 反作弊识别灵敏度 A/B" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="dimension" label="维度" rules={[{ required: true, message: '请选择维度' }]}>
            <Select placeholder="选择维度" style={{ width: 160 }} options={DIMENSIONS.map((d) => ({ label: d, value: d }))} />
          </Form.Item>
          <Form.Item name="variant_a" label="对照组 A" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 现版本规则" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="variant_b" label="实验组 B" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 新版本规则" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="target_percent" label="实验组占比" initialValue={50} rules={[{ required: true, message: '必填' }]}>
            <InputNumber min={0} max={100} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="可选" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" onClick={create}>创建</Button>
          </Form.Item>
        </Form>
      </Card>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<AbExp>
          rowKey="id"
          columns={columns}
          dataSource={list}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 1100 }}
          locale={{ emptyText: '暂无 A/B 实验' }}
        />
      </Card>

      <Modal
        title={sigExp ? `显著性 · ${sigExp.name}` : '显著性'}
        open={sigExp != null}
        onCancel={() => setSigExp(null)}
        footer={null}
      >
        {sigLoading && <Text type="secondary">计算中…</Text>}
        {!sigLoading && sig && (
          <div style={{ fontSize: 14, lineHeight: 2.2 }}>
            <div>对照组 (A) CTR：<b>{(sig.ctr_a * 100).toFixed(2)}%</b></div>
            <div>实验组 (B) CTR：<b>{(sig.ctr_b * 100).toFixed(2)}%</b></div>
            <div>lift：<b>{(sig.lift * 100).toFixed(2)}%</b></div>
            <div>p-value：<b>{sig.p_value.toFixed(6)}</b></div>
            <div>
              结论：
              {sig.significant ? <Tag color="green">差异显著</Tag> : <Tag>差异不显著</Tag>}
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}