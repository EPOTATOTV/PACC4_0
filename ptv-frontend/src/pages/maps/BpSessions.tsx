import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, RightOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { Enrollment, MapBanPickSession, MapPool, MapPoolFormat } from '../../types'

const { Title, Text } = Typography

const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }
const statusColors: Record<string, string> = { PENDING: 'default', ACTIVE: 'processing', PAUSED: 'warning', COMPLETED: 'success', CANCELLED: 'error' }
const formatNames: Record<string, string> = { BO1: 'BO1', BO3: 'BO3', BO5: 'BO5' }

export default function BpSessions() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<MapBanPickSession[]>([])
  const [status, setStatus] = useState<string>('')
  const [err, setErr] = useState('')
  const [open, setOpen] = useState(false)

  // 新建表单
  const [pools, setPools] = useState<MapPool[]>([])
  const [enrollments, setEnrollments] = useState<Enrollment[]>([])
  const [poolId, setPoolId] = useState('')
  const [format, setFormat] = useState<MapPoolFormat>('BO1')
  const [blueId, setBlueId] = useState('')
  const [redId, setRedId] = useState('')
  const [matchId, setMatchId] = useState('')
  const [timeoutSec, setTimeoutSec] = useState(60)
  const [referee, setReferee] = useState('裁判')

  const seed = useCallback(async () => {
    try {
      const [p, e] = await Promise.all([api.maps.pools(), api.competition.enrollments()])
      setPools(p)
      setEnrollments(e.filter((x) => x.status === 'APPROVED'))
    } catch (ee) { setErr((ee as Error).message) }
  }, [])
  useEffect(() => { seed() }, [seed])

  const load = useCallback(async () => {
    try {
      const list = await api.maps.bpSessions()
      setSessions(status ? list.filter((s) => s.status === status) : list)
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }, [status])
  useEffect(() => { load() }, [status, load])

  const approvedRed = enrollments.filter((e) => e.enrollmentId !== redId)

  async function createBp() {
    if (!poolId) return setErr('请选择地图池')
    if (!blueId || !redId) return setErr('请选择蓝方与红方')
    if (blueId === redId) return setErr('蓝方与红方不能相同')
    try {
      const blue = enrollments.find((e) => e.enrollmentId === blueId)
      const red = enrollments.find((e) => e.enrollmentId === redId)
      const s = await api.maps.createBp({
        pool_id: poolId,
        format,
        match_id: matchId,
        blue_enrollment_id: blueId,
        red_enrollment_id: redId,
        blue_team_name: blue?.displayName || `选手 ${mask(blue?.pteid)}`,
        red_team_name: red?.displayName || `选手 ${mask(red?.pteid)}`,
        turn_timeout_seconds: String(timeoutSec || 60),
        referee,
      })
      setOpen(false)
      navigate(`/maps/bp/${s.bpSessionId}`)
    } catch (ee) { setErr((ee as Error).message) }
  }

  const cols: TableColumnsType<MapBanPickSession> = [
    { title: '赛制', dataIndex: 'format', width: 70, render: (v: string) => <Tag bordered={false}>{formatNames[v] ?? v}</Tag> },
    { title: '对阵', key: 'teams', render: (_, s) => (
        <Space size={6} wrap>
          <Tag color="blue">{s.blueTeamName || '蓝方'}</Tag>
          <Text type="secondary">vs</Text>
          <Tag color="red">{s.redTeamName || '红方'}</Tag>
        </Space>
      ) },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => <Tag color={statusColors[v]}>{statusNames[v] ?? v}</Tag> },
    { title: '回合', key: 'turn', width: 90, render: (_, s) => (
        s.status === 'ACTIVE' ? (
          <Text style={{ fontSize: 12 }}>{turnName(s.currentTurn)} · {s.currentRound}/{s.totalRounds}</Text>
        ) : <Text type="secondary" style={{ fontSize: 12 }}>{s.status === 'COMPLETED' ? `${s.totalRounds} 图` : '-'}</Text>
      ) },
    { title: '地图池', dataIndex: 'poolId', width: 120, ellipsis: true, render: (v: string) => pools.find((p) => p.poolId === v)?.name || short(v) },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{fmt(v)}</Text> },
    { title: '', key: 'op', width: 70, render: (_, s) => (
        <Button type="link" size="small" icon={<RightOutlined />} onClick={() => navigate(`/maps/bp/${s.bpSessionId}`)}>控制台</Button>
      ) },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>BP 会话</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          allowClear
          placeholder="按状态筛选"
          style={{ width: 160 }}
          value={status || undefined}
          onChange={(v) => setStatus(v ?? '')}
          options={Object.entries(statusNames).map(([k, v]) => ({ value: k, label: v }))}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建 BP</Button>
      </Space>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<MapBanPickSession>
          rowKey="bpSessionId"
          columns={cols}
          dataSource={sessions}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 760 }}
          locale={{ emptyText: '暂无 BP 会话，点击「新建 BP」开始。' }}
        />
      </Card>

      <Modal title="新建 BP 会话" open={open} okText="创建并进入" cancelText="取消" width={520} onOk={createBp} onCancel={() => setOpen(false)}>
        <Form layout="vertical">
          <Row gutter={12}>
            <Col span={16}>
              <Form.Item label="地图池" required>
                <Select value={poolId} onChange={setPoolId} options={pools.map((p) => ({ value: p.poolId, label: `${p.name}（${p.mapCount} 图）` }))} placeholder="选择地图池" showSearch optionFilterProp="label" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="赛制" required>
                <Select value={format} onChange={setFormat} options={(['BO1', 'BO3', 'BO5'] as MapPoolFormat[]).map((f) => ({ value: f, label: f }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="蓝方（PICK 优先）" required>
                <Select value={blueId} onChange={setBlueId} options={enrollments.map(enrolLabel)} placeholder="选择蓝方" showSearch optionFilterProp="label" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="红方" required>
                <Select value={redId} onChange={setRedId} options={approvedRed.map(enrolLabel)} placeholder="选择红方" showSearch optionFilterProp="label" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="关联对局 ID（可选）">
                <Input value={matchId} onChange={(e) => setMatchId(e.target.value)} placeholder="match_id" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="回合超时（秒）">
                <Input type="number" value={timeoutSec} onChange={(e) => setTimeoutSec(Number(e.target.value))} placeholder="默认 60" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item label="裁判">
                <Input value={referee} onChange={(e) => setReferee(e.target.value)} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  )
}

function enrolLabel(e: Enrollment) {
  return { value: e.enrollmentId, label: `${e.displayName || mask(e.pteid)}（${mask(e.pteid)}）` }
}
function mask(p?: string): string {
  if (!p) return '?'
  return p.length <= 4 ? p : `${p.slice(0, 2)}…${p.slice(-2)}`
}
function short(s: string): string {
  if (!s) return '-'
  return s.length <= 8 ? s : `${s.slice(0, 8)}…`
}
function turnName(t?: string): string {
  if (!t) return '-'
  const [side, action] = t.split('_')
  const sideName = side === 'BLUE' ? '蓝方' : '红方'
  return `${sideName} ${action === 'BAN' ? '禁用' : '选择'}`
}
function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}