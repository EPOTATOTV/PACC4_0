import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Input, Modal, Row, Select, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { AlertRule } from '../types'
import MetricCard from '../components/MetricCard'

const { Title, Text } = Typography

interface AlertMgmtItem {
  id: string
  type: string
  level: number
  status: 'open' | 'acknowledged' | 'resolved'
  message: string
  player?: string
  time: string
}

/**
 * 告警中心：告警分类 Tab、列表、规则管理与统计。
 * 数据来自 /api/admin/alerts 系列，后端未就绪显示空态，不 mock。
 */
export default function AlertCenter() {
  const [level, setLevel] = useState<string>()
  const [list, setList] = useState<AlertMgmtItem[]>([])
  const [rules, setRules] = useState<AlertRule[]>([])
  const [err, setErr] = useState('')
  const [ruleOpen, setRuleOpen] = useState(false)

  async function load() {
    try {
      const [items, rs] = await Promise.all([api.alerts.list(level), api.alerts.rules()])
      setList(items)
      setRules(rs)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [level])

  async function toggleRule(r: AlertRule, enabled: boolean) {
    try {
      await api.alerts.toggleRule(r.id, enabled)
      setRules((p) => p.map((x) => (x.id === r.id ? { ...x, enabled } : x)))
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function ack(id: string) {
    try {
      await api.alerts.ack(id)
      setList((p) => p.map((x) => (x.id === id ? { ...x, status: 'acknowledged' } : x)))
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const openCount = list.filter((a) => a.status === 'open').length

  const columns: TableColumnsType<AlertMgmtItem> = [
    { title: '级别', dataIndex: 'level', width: 80, render: (v: number) => <Tag color={v >= 4 ? 'red' : v >= 3 ? 'orange' : 'gold'}>L{v}</Tag> },
    { title: '类型', dataIndex: 'type', width: 140 },
    { title: '消息内容', dataIndex: 'message' },
    { title: '玩家', dataIndex: 'player', width: 120, render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: '时间', dataIndex: 'time', width: 160, render: (v: string) => <Text type="secondary" style={{ whiteSpace: 'nowrap' }}>{fmt(v)}</Text> },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v: AlertMgmtItem['status']) => <Tag color={v === 'open' ? 'error' : v === 'acknowledged' ? 'warning' : 'success'}>{v}</Tag>,
    },
    { title: '操作', width: 110, render: (_, r) => (r.status === 'open' ? <Button size="small" onClick={() => ack(r.id)}>确认</Button> : <Text type="secondary">—</Text>) },
  ]

  const ruleCols: TableColumnsType<AlertRule> = [
    { title: '规则名', dataIndex: 'name' },
    { title: '范围', dataIndex: 'scope', width: 140 },
    { title: '条件', dataIndex: 'condition', width: 180 },
    { title: '阈值', dataIndex: 'threshold', width: 90 },
    { title: '冷却(分)', dataIndex: 'cooldownMin', width: 100 },
    { title: '渠道', dataIndex: 'channels', width: 160, render: (c: string[]) => (c ?? []).join(' / ') },
    { title: '启用', width: 80, render: (_, r) => <Switch checked={r.enabled} onChange={(v) => toggleRule(r, v)} /> },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>告警中心</Title>
        <Tag color={openCount > 0 ? 'error' : 'success'}>{openCount} 未确认</Tag>
        <div style={{ flex: 1 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setRuleOpen(true)}>新建规则</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[12, 12]} style={{ marginBottom: 14 }} className="pacc-stagger">
        <Col xs={24} sm={12} md={6}><MetricCard label="告警总数" value={list.length} accent="var(--kpi-blue)" /></Col>
        <Col xs={24} sm={12} md={6}><MetricCard label="未确认" value={openCount} accent={openCount > 0 ? 'var(--kpi-red)' : 'var(--kpi-green)'} /></Col>
        <Col xs={24} sm={12} md={6}><MetricCard label="已确认" value={list.filter((a) => a.status === 'acknowledged').length} accent="var(--kpi-amber)" /></Col>
        <Col xs={24} sm={12} md={6}><MetricCard label="已解决" value={list.filter((a) => a.status === 'resolved').length} accent="var(--kpi-green)" /></Col>
      </Row>

      <Card
        title="告警列表"
        size="small"
        style={{ marginBottom: 14 }}
        extra={
          <Select
            allowClear
            placeholder="按级别筛选"
            style={{ width: 150 }}
            value={level}
            onChange={(v) => setLevel(v)}
            options={[1, 2, 3, 4].map((v) => ({ value: String(v), label: `L${v}` }))}
          />
        }
      >
        <Table<AlertMgmtItem> rowKey="id" columns={columns} dataSource={list} pagination={{ pageSize: 10 }} size="small" scroll={{ x: 820 }} locale={{ emptyText: '暂无告警' }} />
      </Card>

      <Card title="告警规则" size="small">
        <Table<AlertRule> rowKey="id" columns={ruleCols} dataSource={rules} pagination={false} size="small" scroll={{ x: 820 }} locale={{ emptyText: '暂无规则' }} />
      </Card>

      <Modal title="新建告警规则" open={ruleOpen} onCancel={() => setRuleOpen(false)} onOk={() => { message.success('规则已保存（后端就绪后生效）'); setRuleOpen(false) }} okText="保存" cancelText="取消">
        <Alert type="info" message="在告警规则接口(后端)就绪前，此处为规则配置入口骨架。" style={{ marginBottom: 16 }} />
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input placeholder="规则名称" />
          <Select placeholder="告警范围" options={['红屏', '检测引擎', '网络', 'AI 模型'].map((v) => ({ value: v, label: v }))} />
        </Space>
      </Modal>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}