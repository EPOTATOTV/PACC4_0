import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space, Table, Tabs, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { AdminLoginLog, CheatRecord, InspectSession } from '../types'

const { Title, Text } = Typography

function downloadCsv(filename: string, rows: string[][]) {
  const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export default function Audit() {
  const [logs, setLogs] = useState<AdminLoginLog[]>([])
  const [inspects, setInspects] = useState<InspectSession[]>([])
  const [records, setRecords] = useState<CheatRecord[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState({ logs: true, inspects: true, records: true })

  useEffect(() => {
    api.adminLoginLogs()
      .then((d) => setLogs(d.logs))
      .catch((e) => setErr(String(e.message || e)))
      .finally(() => setLoading((l) => ({ ...l, logs: false })))
    api.inspects.all()
      .then(setInspects)
      .catch(() => {})
      .finally(() => setLoading((l) => ({ ...l, inspects: false })))
    api.records.list()
      .then(setRecords)
      .catch((e) => setErr(String((e as Error)?.message || e)))
      .finally(() => setLoading((l) => ({ ...l, records: false })))
  }, [])

  const stateTag = (s: string) => {
    const map: Record<string, React.ReactNode> = {
      QUEUED: <Tag>排队中</Tag>,
      ACTIVE: <Tag color="processing">查端中</Tag>,
      DONE: <Tag color="success">已完成</Tag>,
      TIMEOUT: <Tag color="warning">超时</Tag>,
      CANCELLED: <Tag>取消</Tag>,
    }
    return map[s] ?? <Tag>{s}</Tag>
  }

  const loginCols: TableColumnsType<AdminLoginLog> = [
    { title: '时间', dataIndex: 'created_at', width: 180, render: (v: string) => new Date(v).toLocaleString() },
    { title: '身份（脱敏）', dataIndex: 'identity', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '方式', dataIndex: 'method', width: 90, render: (m: string) => (m === 'feishu' ? <Tag color="geekblue">飞书</Tag> : <Tag color="gold">密钥</Tag>) },
    { title: '角色', dataIndex: 'role', width: 120, render: (r?: string | null) => r === 'super-admin' ? <Tag color="volcano">超管</Tag> : r === 'operator' ? <Tag color="cyan">运维</Tag> : <Text type="secondary">—</Text> },
    { title: '结果', dataIndex: 'result', width: 90, render: (r: string) => (r === 'success' ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>) },
    { title: '来源 IP', dataIndex: 'ip', width: 150, render: (ip: string) => <span style={{ fontFamily: 'monospace' }}>{ip}</span> },
  ]

  const inspectCols: TableColumnsType<InspectSession> = [
    { title: '会话', dataIndex: 'sessionId', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '状态', dataIndex: 'state', width: 110, render: (s: InspectSession['state']) => stateTag(s) },
    { title: '结论', dataIndex: 'conclusion', width: 130, render: (c?: string) => c ?? <Text type="secondary">—</Text> },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString() : '—') },
  ]

  const recordCols: TableColumnsType<CheatRecord> = [
    { title: '记录', dataIndex: 'recordId', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '类型', dataIndex: 'cheatType' },
    { title: '等级', dataIndex: 'level', width: 90, render: (v: number) => `${v} 级` },
    { title: '风险分', dataIndex: 'riskScore', width: 90 },
    { title: '状态', dataIndex: 'revoked', width: 110, render: (r: boolean) => (r ? <Tag color="warning">已撤销</Tag> : <Tag color="error">在案</Tag>) },
    { title: '时间', dataIndex: 'occurredAt', width: 180, render: (v: string) => new Date(v).toLocaleString() },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>审计日志</Title>
      <Text type="secondary">集中汇总管理端操作痕迹：登录记录、远程查端记录与作弊记录/撤销，供合规追溯与核对。</Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Card
        style={{ marginTop: 20 }}
        styles={{ body: { paddingTop: 8 } }}
        extra={
          <Button size="small" onClick={() => downloadCsv('login-logs.csv', [
            ['时间', '身份', '方式', '角色', '结果', '来源IP'],
            ...logs.map((l) => [l.created_at, l.identity, l.method, l.role ?? '', l.result, l.ip]),
          ])}>导出登录日志 CSV</Button>
        }
      >
        <Tabs
          items={[
            {
              key: 'login',
              label: '登录记录',
              children: (
                <Table<AdminLoginLog> rowKey="id" columns={loginCols} dataSource={logs} loading={loading.logs}
                  pagination={{ pageSize: 10, hideOnSinglePage: true }} scroll={{ x: 720 }} locale={{ emptyText: '暂无记录' }} />
              ),
            },
            {
              key: 'inspect',
              label: '远程查端记录',
              children: (
                <Table<InspectSession> rowKey="sessionId" columns={inspectCols} dataSource={inspects} loading={loading.inspects}
                  pagination={{ pageSize: 10, hideOnSinglePage: true }} scroll={{ x: 720 }} locale={{ emptyText: '暂无记录' }} />
              ),
            },
            {
              key: 'record',
              label: '作弊/撤销记录',
              children: (
                <Table<CheatRecord> rowKey="recordId" columns={recordCols} dataSource={records} loading={loading.records}
                  pagination={{ pageSize: 10, hideOnSinglePage: true }} scroll={{ x: 760 }} locale={{ emptyText: '暂无记录' }} />
              ),
            },
          ]}
        />
      </Card>
      <Space style={{ marginTop: 12 }} />
    </div>
  )
}