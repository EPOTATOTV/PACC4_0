import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import MetricCard from '../components/MetricCard'

const { Title, Text } = Typography

const CATEGORIES = ['APPEAL', 'TECHNICAL', 'ACCOUNT', 'FEATURE', 'BUSINESS', 'REPORT']
const PRIORITIES = ['P0', 'P1', 'P2', 'P3']

const categoryLabel: Record<string, string> = {
  APPEAL: '申诉',
  TECHNICAL: '技术',
  ACCOUNT: '账号',
  FEATURE: '功能',
  BUSINESS: '商务',
  REPORT: '举报',
}
const statusColor: Record<string, string> = {
  OPEN: 'blue',
  RESPONDED: 'gold',
  RESOLVED: 'green',
  CLOSED: 'default',
}
const priorityColor: Record<string, string> = { P0: 'red', P1: 'orange', P2: 'cyan', P3: 'default' }

interface Ticket {
  id: string
  pteid?: string
  category?: string
  title?: string
  description?: string
  status?: string
  priority?: string
  assignee?: string
  firstReplyAt?: string
  resolvedAt?: string
  createdAt?: string
}

interface FaqItem {
  id: string
  question?: string
  answer?: string
  keywords?: string
  createdAt?: string
}

interface Dashboard {
  open_count: number
  by_category: Record<string, number>
  by_priority: Record<string, number>
  avg_first_reply_seconds: number
  smart_hit_rate: number
}

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const res = await fetch(`/api/admin${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

function fmtTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function fmtSec(n?: number): string {
  if (n == null) return '-'
  const v = Number(n)
  if (v < 60) return `${Math.round(v)}s`
  if (v < 3600) return `${(v / 60).toFixed(1)}min`
  return `${(v / 3600).toFixed(1)}h`
}

export default function SupportCenter() {
  const [tickets, setTickets] = useState<Ticket[]>([])
  const [faqs, setFaqs] = useState<FaqItem[]>([])
  const [dash, setDash] = useState<Dashboard | null>(null)
  const [err, setErr] = useState('')

  const [createOpen, setCreateOpen] = useState(false)
  const [replyOpen, setReplyOpen] = useState(false)
  const [replyTicket, setReplyTicket] = useState<Ticket | null>(null)
  const [slaResult, setSlaResult] = useState<{ first_reply_seconds: number; budget: number; in_sla: boolean; priority: string } | null>(null)
  const [faqForm] = Form.useForm()
  const [createForm] = Form.useForm()
  const [replyForm] = Form.useForm()

  const load = useCallback(async () => {
    try {
      const [tk, fq, db] = await Promise.all([
        api<Ticket[]>('/support/tickets'),
        api<FaqItem[]>('/support/faq'),
        api<Dashboard>('/support/dashboard'),
      ])
      setTickets(tk)
      setFaqs(fq)
      setDash(db)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function onCreate(values: Record<string, string>) {
    try {
      await api('/support/tickets', {
        method: 'POST',
        body: JSON.stringify({
          pteid: values.pteid || '',
          category: values.category || 'TECHNICAL',
          title: values.title,
          description: values.description || '',
        }),
      })
      message.success('工单已创建')
      createForm.resetFields()
      setCreateOpen(false)
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  function onReply(t: Ticket) {
    setReplyTicket(t)
    setSlaResult(null)
    replyForm.resetFields()
    setReplyOpen(true)
  }

  async function doReply(values: Record<string, string>) {
    if (!replyTicket) return
    try {
      const res = await api<{ sla_info: { priority: string; first_reply_seconds: number; sla_budget_seconds: number; in_sla: boolean } }>(
        `/support/tickets/${replyTicket.id}/reply`,
        { method: 'POST', body: JSON.stringify({ reply: values.reply, responder: values.responder || 'admin' }) },
      )
      const s = res.sla_info
      setSlaResult({ first_reply_seconds: s.first_reply_seconds, budget: s.sla_budget_seconds, in_sla: s.in_sla, priority: s.priority })
      message.success('已回复')
      setReplyOpen(false)
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function onResolve(t: Ticket) {
    try {
      await api(`/support/tickets/${t.id}/resolve`, { method: 'POST', body: JSON.stringify({ responder: 'admin' }) })
      message.success('工单已解决')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function onAddFaq(values: Record<string, string>) {
    try {
      await api('/support/faq', {
        method: 'POST',
        body: JSON.stringify({ question: values.question, answer: values.answer, keywords: values.keywords || '' }),
      })
      message.success('FAQ 已新增')
      faqForm.resetFields()
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function onDeleteFaq(id: string) {
    try {
      await api(`/support/faq/${id}`, { method: 'DELETE' })
      message.success('已删除')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  const ticketColumns: ColumnsType<Ticket> = [
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: fmtTime, width: 160 },
    { title: '分类', dataIndex: 'category', key: 'category', width: 90, render: (c) => <Tag>{categoryLabel[c] ?? c}</Tag> },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      width: 90,
      render: (p) => <Tag color={(priorityColor[p] ?? 'default') as string}>{p}</Tag>,
    },
    { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: (s) => <Tag color={(statusColor[s] ?? 'default') as string}>{s}</Tag> },
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    { title: '处理人', dataIndex: 'assignee', key: 'assignee', width: 100, render: (a) => a || '-' },
    {
      title: '操作',
      key: 'op',
      width: 130,
      render: (_: unknown, r: Ticket) => (
        <span>
          <Button size="small" type="link" onClick={() => onReply(r)}>回复</Button>
          {r.status !== 'RESOLVED' && r.status !== 'CLOSED' && (
            <Popconfirm title="确认解决该工单？" onConfirm={() => onResolve(r)}>
              <Button size="small" type="link">解决</Button>
            </Popconfirm>
          )}
        </span>
      ),
    },
  ]

  const faqColumns: ColumnsType<FaqItem> = [
    { title: '问题', dataIndex: 'question', key: 'question', width: 200 },
    { title: '回答', dataIndex: 'answer', key: 'answer', ellipsis: true },
    { title: '关键词', dataIndex: 'keywords', key: 'keywords', width: 180, render: (k) => k || '-' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: fmtTime, width: 160 },
    {
      title: '操作',
      key: 'op',
      width: 70,
      render: (_: unknown, r: FaqItem) => (
        <Popconfirm title="删除该 FAQ？" onConfirm={() => onDeleteFaq(r.id)}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      ),
    },
  ]

  const prio = dash?.by_priority ?? {}

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>客服工单中心</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        工单创建 / 分类 / SLA 首响跟踪 / 智能回复(FAG) / 知识库，简单数据分析。
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 18 }} closable />}

      <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: 20 }}>
        <Card style={{ minWidth: 160 }}>
          <MetricCard label="开放工单" value={dash?.open_count ?? 0} accent="var(--kpi-red)" />
        </Card>
        <Card style={{ minWidth: 160 }}>
          <MetricCard label="平均首响" value={fmtSec(dash?.avg_first_reply_seconds)} />
        </Card>
        <Card style={{ minWidth: 160 }}>
          <MetricCard label="智能命中率" value={dash ? (dash.smart_hit_rate * 100).toFixed(1) : '0'} hint="%" />
        </Card>
        <Card style={{ minWidth: 220 }}>
          <MetricCard label="各优先级计数" value={PRIORITIES.map((p) => `${p}:${prio[p] ?? 0}`).join('  ')} />
        </Card>
      </div>

      <Tabs
        type="card"
        items={[
          {
            key: 'tickets',
            label: '工单',
            children: (
              <Card size="small" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none' }} styles={{ body: { padding: 12 } }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                  <span style={{ fontWeight: 600 }}>工单列表</span>
                  <Badge count={tickets.length} offset={[6, 0]} />
                  <Button type="primary" size="small" onClick={() => setCreateOpen(true)}>创建工单</Button>
                </div>
                <Table rowKey="id" size="small" dataSource={tickets} columns={ticketColumns} pagination={{ pageSize: 10, showSizeChanger: false }} />
              </Card>
            ),
          },
          {
            key: 'faq',
            label: 'FAQ 知识库',
            children: (
              <div>
                <Card size="small" title="新增 FAQ" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none', marginBottom: 16 }}>
                  <Form form={faqForm} layout="inline" onFinish={onAddFaq}>
                    <Form.Item name="question" rules={[{ required: true, message: '请输入问题' }]} style={{ width: 240 }}>
                      <Input placeholder="问题" />
                    </Form.Item>
                    <Form.Item name="answer" rules={[{ required: true, message: '请输入回答' }]} style={{ width: 360 }}>
                      <Input placeholder="官方回答" />
                    </Form.Item>
                    <Form.Item name="keywords">
                      <Input placeholder="关键词(逗号分隔)" />
                    </Form.Item>
                    <Form.Item>
                      <Button type="primary" htmlType="submit">新增</Button>
                    </Form.Item>
                  </Form>
                </Card>
                <Card size="small" style={{ border: '1px solid var(--border-strong)', boxShadow: 'none' }} styles={{ body: { padding: 0 } }}>
                  <Table rowKey="id" size="small" dataSource={faqs} columns={faqColumns} pagination={{ pageSize: 10, showSizeChanger: false }} />
                </Card>
              </div>
            ),
          },
        ]}
      />

      <Modal title="创建工单" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => createForm.submit()} destroyOnClose>
        <Form form={createForm} layout="vertical" onFinish={onCreate}>
          <Form.Item name="category" label="分类" initialValue="TECHNICAL">
            <Select options={CATEGORIES.map((c) => ({ value: c, label: `${c} · ${categoryLabel[c]}` }))} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="工单标题" />
          </Form.Item>
          <Form.Item name="pteid" label="PTEID">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="问题描述" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`回复工单 · ${replyTicket?.title ?? ''}`}
        open={replyOpen}
        onCancel={() => setReplyOpen(false)}
        onOk={() => replyForm.submit()}
        destroyOnClose
      >
        {slaResult && (
          <Alert
            style={{ marginBottom: 12 }}
            type={slaResult.in_sla ? 'success' : 'error'}
            message={`SLA(${slaResult.priority})：首响 ${fmtSec(slaResult.first_reply_seconds)} / 预算 ${fmtSec(slaResult.budget)} → ${slaResult.in_sla ? '未超时' : '已超时'}`}
          />
        )}
        <Form form={replyForm} layout="vertical" onFinish={doReply}>
          <Form.Item name="reply" label="回复内容" rules={[{ required: true, message: '请输入回复' }]}>
            <Input.TextArea rows={4} placeholder="回复内容" />
          </Form.Item>
          <Form.Item name="responder" label="处理人" initialValue="admin">
            <Input placeholder="处理人" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}