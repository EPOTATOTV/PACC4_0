import { useEffect, useState } from 'react'
import {
  Alert, Button, Card, DatePicker, Input, Space, Table, Tabs, Tag, Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { AdminLoginLog, CheatRecord, InspectSession } from '../types'
import MetricCard from '../components/MetricCard'

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

  // ---- v4.8 管理员操作审计 ----
  const [ops, setOps] = useState<Record<string, unknown>[]>([])
  const [opsTotal, setOpsTotal] = useState(0)
  const [opsPage, setOpsPage] = useState(0)
  const [opsLoading, setOpsLoading] = useState(false)
  const [opFilter, setOpFilter] = useState({ actor: '', action: '', range: null as [string, string] | null })
  const [overview, setOverview] = useState<{ total: number; error_rate: number; error_count: number; by_status: Record<string, number>; top_actions: { action: string; count: number }[] } | null>(null)

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

  const loadOps = (page = 0) => {
    setOpsLoading(true)
    const p: Record<string, string> = { page: String(page), size: '15' }
    if (opFilter.actor) p.actor = opFilter.actor
    if (opFilter.action) p.action = opFilter.action
    if (opFilter.range) {
      p.startDate = new Date(opFilter.range[0]).toISOString()
      p.endDate = new Date(opFilter.range[1]).toISOString()
    }
    api.audit.operations(p)
      .then((d) => { setOps(d.rows ?? []); setOpsTotal(d.total ?? 0); setOpsPage(d.page ?? page) })
      .catch((e) => setErr(String(e.message || e)))
      .finally(() => setOpsLoading(false))
  }

  const loadOverview = () => {
    api.audit.overview(30).then(setOverview).catch(() => {})
  }

  useEffect(() => { loadOps(0); loadOverview() }, []) // eslint-disable-line react-hooks/exhaustive-deps

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

  const opCols: TableColumnsType<Record<string, unknown>> = [
    { title: '时间', dataIndex: 'created_at', width: 170, render: (v) => new Date(String(v)).toLocaleString() },
    { title: '操作人', dataIndex: 'actor', width: 130, render: (v) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '动作', dataIndex: 'action', width: 160, render: (v) => <Tag color="geekblue">{v}</Tag> },
    { title: '对象', dataIndex: 'entity_type', width: 100, render: (v, r) => <span>{v}<Text type="secondary">{r.entity_id ? ` · ${r.entity_id}` : ''}</Text></span> },
    { title: '方式', dataIndex: 'http_method', width: 80 },
    { title: '路径', dataIndex: 'http_path', ellipsis: true },
    { title: '结果', dataIndex: 'http_status', width: 90, render: (v) => {
      const code = Number(v)
      return code >= 400 ? <Tag color="error">{code}</Tag> : <Tag color="success">{code}</Tag>
    } },
    { title: '来源', dataIndex: 'ip', width: 130, render: (v) => <span style={{ fontFamily: 'monospace' }}>{v ?? '—'}</span> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>审计日志</Title>
      <Text type="secondary">集中汇总管理端操作痕迹：管理员操作审计、登录记录、远程查端记录与作弊记录/撤销，供合规追溯与核对。</Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Card style={{ marginTop: 20 }} styles={{ body: { paddingTop: 8 } }}>
        <Tabs
          defaultActiveKey="operations"
          items={[
            {
              key: 'operations',
              label: '管理员操作审计',
              children: (
                <Space direction="vertical" style={{ width: '100%' }} size={16}>
                  <Space wrap>
                    <Input
                      placeholder="操作人" allowClear value={opFilter.actor} style={{ width: 170 }}
                      onChange={(e) => setOpFilter((f) => ({ ...f, actor: e.target.value }))} />
                    <Input
                      placeholder="动作（如 accounts.lock）" allowClear value={opFilter.action} style={{ width: 210 }}
                      onChange={(e) => setOpFilter((f) => ({ ...f, action: e.target.value }))} />
                    <DatePicker.RangePicker
                      style={{ width: 260 }}
                      onChange={(v) => setOpFilter((f) => ({ ...f, range: v ? [v[0]!.toISOString(), v[1]!.toISOString()] : null }))} />
                    <Button type="primary" onClick={() => loadOps(0)}>查询</Button>
                    <Button onClick={() => { setOpFilter({ actor: '', action: '', range: null }); loadOps(0) }}>重置</Button>
                    <Button onClick={() => downloadCsv('admin-operations.csv', [
                      ['时间', '操作人', '角色', '动作', '对象类型', '对象ID', '方式', '路径', '状态', '来源IP'],
                      ...ops.map((o) => [String(o.created_at), String(o.actor), String(o.role ?? ''), String(o.action), String(o.entity_type ?? ''), String(o.entity_id ?? ''), String(o.http_method), String(o.http_path), String(o.http_status), String(o.ip ?? '')]),
                    ])}>导出 CSV</Button>
                  </Space>

                  {overview && (
                    <Card size="small" styles={{ body: { paddingLeft: 20 } }}>
                      <Space size={48} wrap>
                        <MetricCard label="近 30 天敏感操作" value={overview.total} accent="var(--kpi-blue)" />
                        <MetricCard label="失败操作" value={overview.error_count} accent={overview.error_rate > 5 ? 'var(--kpi-red)' : 'var(--kpi-green)'} />
                        <MetricCard label="失败率" value={overview.error_rate.toFixed(2)} hint="%" accent={overview.error_rate > 5 ? 'var(--kpi-red)' : 'var(--kpi-green)'} />
                        <div>
                          <Text type="secondary" style={{ fontSize: 12 }}>状态码分布</Text>
                          <div style={{ marginTop: 4 }}>
                            {Object.entries(overview.by_status).map(([k, v]) => (
                              <Tag key={k} color={Number(k) >= 400 ? 'error' : 'success'}>{k}: {v}</Tag>
                            ))}
                          </div>
                        </div>
                        <div>
                          <Text type="secondary" style={{ fontSize: 12 }}>高频操作 TOP</Text>
                          <div style={{ marginTop: 4, maxWidth: 320 }}>
                            {overview.top_actions.slice(0, 5).map((a, i) => (
                              <div key={`${a.action}-${i}`} style={{ fontSize: 12, marginBottom: 2 }}>
                                <Tag>{a.count}</Tag> {a.action}
                              </div>
                            ))}
                          </div>
                        </div>
                      </Space>
                    </Card>
                  )}

                  <Table<Record<string, unknown>>
                    rowKey="id" columns={opCols} dataSource={ops} loading={opsLoading} size="small"
                    pagination={{
                      current: opsPage + 1,
                      pageSize: 15,
                      total: opsTotal,
                      showSizeChanger: false,
                      onChange: (pg) => loadOps(pg - 1),
                    }}
                    scroll={{ x: 980 }} locale={{ emptyText: '暂无操作记录' }} />
                </Space>
              ),
            },
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