import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Statistic, Table, Tabs, Tag, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { AdminPlayerDetail, Appeal, CheatRecord, DeviceRecord, InspectSession } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title, Text } = Typography

/**
 * 管理端玩家详情页：基本信息、信誉、设备、申诉、查端、记录。
 * 数据来自 /api/admin/players/:pteid，后端未就绪显示空态。
 */
export default function PlayerDetail() {
  const { pteid } = useParams<{ pteid: string }>()
  const navigate = useNavigate()
  const [d, setD] = useState<AdminPlayerDetail | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!pteid) return
    api.playerDetail(pteid).then(setD).catch((e) => setErr((e as Error).message))
  }, [pteid])

  const deviceCols: TableColumnsType<DeviceRecord> = [
    { title: '设备 ID', dataIndex: 'deviceId' },
    { title: '平台', dataIndex: 'platform', render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: '名称', dataIndex: 'deviceName', render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: 'IP', dataIndex: 'ip', render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: '最后登录', dataIndex: 'lastLoginAt', render: (v?: string) => (v ? fmt(v) : <Text type="secondary">-</Text>) },
  ]

  const appealCols: TableColumnsType<Appeal> = [
    { title: '类型', dataIndex: 'reason' },
    { title: '状态', dataIndex: 'status', width: 110, render: (v: Appeal['status']) => <StatusPill value={v} /> },
    { title: '提交时间', dataIndex: 'createdAt', render: (v?: string) => fmt(v) },
  ]

  const inspectCols: TableColumnsType<InspectSession> = [
    { title: '会话 ID', dataIndex: 'sessionId' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '操作人', dataIndex: 'operator', render: (v?: string) => v || <Text type="secondary">-</Text> },
    { title: '结论', dataIndex: 'conclusion', render: (v?: string) => v || <Text type="secondary">-</Text> },
  ]

  const recordCols: TableColumnsType<CheatRecord> = [
    { title: '类型', dataIndex: 'cheatType' },
    { title: '风险', dataIndex: 'riskScore', width: 90, render: (v: number) => <Tag color={v >= 80 ? 'error' : v >= 60 ? 'warning' : 'success'}>{v}</Tag> },
    { title: '时间', dataIndex: 'occurredAt', render: (v?: string) => fmt(v) },
  ]

  const tabs = [
    { key: 'devices', label: `设备 (${d?.devices.length ?? 0})`, children: <Table size="small" rowKey="deviceId" columns={deviceCols} dataSource={d?.devices ?? []} pagination={false} locale={{ emptyText: '暂无设备' }} /> },
    { key: 'appeals', label: `申诉 (${d?.appeals.length ?? 0})`, children: <Table size="small" rowKey="appealId" columns={appealCols} dataSource={d?.appeals ?? []} pagination={false} locale={{ emptyText: '暂无申诉' }} /> },
    { key: 'inspects', label: `查端 (${d?.inspects.length ?? 0})`, children: <Table size="small" rowKey="sessionId" columns={inspectCols} dataSource={d?.inspects ?? []} pagination={false} locale={{ emptyText: '暂无查端' }} /> },
    { key: 'records', label: `作弊记录 (${d?.records.length ?? 0})`, children: <Table size="small" rowKey="recordId" columns={recordCols} dataSource={d?.records ?? []} pagination={false} locale={{ emptyText: '暂无记录' }} /> },
  ]

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/accounts')} style={{ marginBottom: 16 }}>返回账号列表</Button>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      {!d && !err ? (
        <Empty description="加载中…" style={{ padding: 48 }} />
      ) : d ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
            <Title level={3} style={{ margin: 0, fontFamily: 'monospace' }}>{d.pteid}</Title>
            <StatusPill value={d.status} />
          </div>

          <Row gutter={[14, 14]} style={{ marginBottom: 14 }}>
            <Col xs={12} sm={6}><Card size="small"><Statistic title="信誉分" value={d.reputation} suffix="/100" valueStyle={{ color: d.reputation >= 70 ? '#3fb950' : d.reputation >= 40 ? '#d29922' : '#ff3b30' }} /></Card></Col>
            <Col xs={12} sm={6}><Card size="small"><Statistic title="红屏次数" value={d.totalRedscreen} valueStyle={{ color: '#ff3b30' }} /></Card></Col>
            <Col xs={12} sm={6}><Card size="small"><Statistic title="设备数" value={d.devices.length} /></Card></Col>
            <Col xs={12} sm={6}><Card size="small"><Statistic title="作弊记录" value={d.records.length} /></Card></Col>
          </Row>

          <Card title="基本信息" size="small" style={{ marginBottom: 14 }}>
            <Descriptions column={3} size="small">
              <Descriptions.Item label="邮箱">{d.email || '-'}</Descriptions.Item>
              <Descriptions.Item label="注册时间">{fmt(d.registeredAt)}</Descriptions.Item>
              <Descriptions.Item label="最近活跃">{fmt(d.lastActiveAt)}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="设备与历史" size="small">
            <Tabs items={tabs} />
          </Card>
        </>
      ) : null}
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}