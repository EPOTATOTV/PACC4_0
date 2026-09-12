import { useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Col, Form, Input, InputNumber, Row,
  Select, Statistic, Table, Tabs, Tag, Typography, message,
} from 'antd'
import { api } from '../api/client'

const { Title, Text } = Typography

type AnyRec = Record<string, any>

function str(o: unknown): string {
  return o == null ? '' : String(o)
}
function fmtTime(s: unknown): string {
  if (!s) return '-'
  const d = new Date(String(s))
  return isNaN(d.getTime()) ? String(s) : d.toLocaleString('zh-CN', { hour12: false })
}

const deterColor: Record<string, string> = {
  BLOCK: 'red',
  ISOLATE: 'volcano',
  MONITOR: 'gold',
  IGNORE: 'default',
}

/* ---------- 家族谱系（血缘）图：节点环形排布，边为 Jaccard 相似度 ---------- */
function FamilyGraph({ graph }: { graph: AnyRec | null }) {
  const nodes = graph?.nodes ?? []
  const links = graph?.links ?? []
  if (!nodes.length) return <Text type="secondary">暂无已聚类的家族数据，请先在「检测深化 v4.6」录入并聚类</Text>

  const size = 520
  const cx = size / 2
  const cy = size / 2
  const r = size / 2 - 56
  const pos = new Map<string, { x: number; y: number }>()
  nodes.forEach((n: AnyRec, i: number) => {
    const ang = (i / nodes.length) * Math.PI * 2 - Math.PI / 2
    pos.set(str(n.id), { x: cx + r * Math.cos(ang), y: cy + r * Math.sin(ang) })
  })

  return (
    <div style={{ textAlign: 'center' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        {links.map((l: AnyRec, i: number) => {
          const s = pos.get(str(l.source))
          const t = pos.get(str(l.target))
          if (!s || !t) return null
          return (
            <line
              key={i} x1={s.x} y1={s.y} x2={t.x} y2={t.y}
              stroke={Number(l.value) > 0.3 ? '#58a6ff' : '#2a2e38'}
              strokeWidth={Math.max(1, Number(l.value) * 4)}
              opacity={0.7}
            />
          )
        })}
        {nodes.map((n: AnyRec) => {
          const p = pos.get(str(n.id))
          if (!p) return null
          const d = Math.min(44, 18 + Number(n.size) * 6)
          return (
            <g key={str(n.id)}>
              <circle cx={p.x} cy={p.y} r={d / 2} fill="#101319" stroke="#8b5cf6" strokeWidth={2} />
              <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize={11} fill="#c9d1d9">
                {str(n.name)}
              </text>
              <title>{`${str(n.name)} · ${str(n.size)} 样本`}</title>
            </g>
          )
        })}
      </svg>
      <div style={{ color: '#8b949e', fontSize: 12, marginTop: 8 }}>
        节点 = AI 家族（面积对应样本数），连线 = 样本间的相似度关系
      </div>
    </div>
  )
}

export default function V47ThreatIntel() {
  const [family, setFamily] = useState<AnyRec | null>(null)
  const [graph, setGraph] = useState<AnyRec | null>(null)
  const [deter, setDeter] = useState<AnyRec | null>(null)
  const [ioc, setIoc] = useState<AnyRec | null>(null)
  const [query, setQuery] = useState<AnyRec>({ page: 0, size: 20, q: '', type: '', state: '' })
  const [err, setErr] = useState('')

  const loadAll = () => {
    api.v47.familyOverview().then(setFamily).catch(() => setFamily(null))
    api.v47.familyGraph().then(setGraph).catch(() => setGraph(null))
    api.v47.deterOverview().then(setDeter).catch(() => setDeter(null))
    api.v47.iocOverview().then(setIoc).catch(() => setIoc(null))
  }
  const loadIoc = () => api.v47.iocList(query).then(setIocList).catch((e: Error) => setErr(e.message))
  const [iocList, setIocList] = useState<AnyRec>({ items: [], total: 0 })

  useEffect(() => {
    loadAll()
  }, [])
  useEffect(() => {
    loadIoc()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  const families = family?.families ?? []
  const familyColumns = useMemo(
    () => [
      { title: 'AI 家族', key: 'family', render: (_: unknown, r: AnyRec) => <Tag color="purple">{r.family ?? '-'}</Tag> },
      { title: '样本数', key: 'size', width: 90, render: (_: unknown, r: AnyRec) => Number(r.size ?? 0) },
      { title: '平均严重度', key: 'severity', width: 110, render: (_: unknown, r: AnyRec) => Number(r.severity ?? 0) },
      { title: '已确认', key: 'confirmed', width: 90, render: (_: unknown, r: AnyRec) => Number(r.confirmed ?? 0) },
      {
        title: '版本分布', key: 'editions', ellipsis: true,
        render: (_: unknown, r: AnyRec) => {
          const m = r.editions ?? {}
          return Object.entries(m).map(([k, v]) => <Tag key={String(k)}>{String(k)}×{String(v)}</Tag>)
        },
      },
      { title: '最近出现', key: 'lastSeen', width: 160, render: (_: unknown, r: AnyRec) => fmtTime(r.last_seen) },
    ],
    [],
  )

  const deterPolicies = deter?.policies ?? []
  const deterColumns = useMemo(
    () => [
      { title: '作用域', key: 'scope', width: 110, render: (_: unknown, r: AnyRec) => <Tag>{r.scopeType ?? '-'}</Tag> },
      { title: '目标', key: 'scopeValue', render: (_: unknown, r: AnyRec) => <code>{r.scopeValue ?? '-'}</code> },
      { title: '处置', key: 'action', width: 110, render: (_: unknown, r: AnyRec) => <Tag color={deterColor[r.action] ?? 'default'}>{r.action ?? '-'}</Tag> },
      { title: '严重度', key: 'severity', width: 90, render: (_: unknown, r: AnyRec) => Number(r.severity ?? 0) },
      { title: '备注', key: 'note', ellipsis: true, render: (_: unknown, r: AnyRec) => r.note ?? '-' },
      {
        title: '启用', key: 'enabled', width: 100,
        render: (_: unknown, r: AnyRec) => <Tag color={r.enabled ? 'success' : 'default'}>{r.enabled ? '启用' : '停用'}</Tag>,
      },
      {
        title: '操作', key: 'actions', width: 90,
        render: (_: unknown, r: AnyRec) => (
          <Button size="small" onClick={() => api.v47.toggleDeter(r.id, !r.enabled).then(loadAll)}>
            {r.enabled ? '停用' : '启用'}
          </Button>
        ),
      },
    ],
    [],
  )

  const iocItems = (iocList.items ?? []) as AnyRec[]
  const iocColumns = useMemo(
    () => [
      { title: '值', key: 'value', width: 280, render: (_: unknown, r: AnyRec) => <code style={{ wordBreak: 'break-all' }}>{r.value ?? '-'}</code> },
      { title: '类型', key: 'type', width: 120, render: (_: unknown, r: AnyRec) => <Tag>{r.type ?? '-'}</Tag> },
      { title: '风险', key: 'severity', width: 80, render: (_: unknown, r: AnyRec) => <Tag color={Number(r.severity) >= 4 ? 'error' : 'default'}>{r.severity ?? '-'}</Tag> },
      { title: '来源家族', key: 'sourceFamily', width: 120, render: (_: unknown, r: AnyRec) => r.sourceFamily ?? '-' },
      { title: '命中', key: 'hitCount', width: 80, render: (_: unknown, r: AnyRec) => Number(r.hitCount ?? 0) },
      {
        title: '状态', key: 'state', width: 100,
        render: (_: unknown, r: AnyRec) => <Tag color={r.state === 'OPEN' ? 'processing' : 'default'}>{r.state ?? '-'}</Tag>,
      },
      { title: '订阅', key: 'subscribed', width: 90, render: (_: unknown, r: AnyRec) => (r.subscribed ? <Tag color="gold">已订阅</Tag> : <Text type="secondary">-</Text>) },
      { title: '最近命中', key: 'lastSeen', width: 150, render: (_: unknown, r: AnyRec) => fmtTime(r.last_seen) },
      {
        title: '操作', key: 'actions', width: 210,
        render: (_: unknown, r: AnyRec) => (
          <span style={{ display: 'flex', gap: 6 }}>
            <Button size="small" type="primary" ghost onClick={() => api.v47.subscribeIoc(r.id).then(loadIoc)}>订阅</Button>
            <Button size="small" onClick={() => api.v47.disarmIoc(r.id).then(loadIoc)}>出示</Button>
            <Button size="small" onClick={() => api.v47.hitIoc(r.id).then(() => { loadIoc(); loadAll() })}>命中</Button>
          </span>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  const [importSample, setImportSample] = useState('')
  function doImport() {
    if (!importSample.trim()) return message.warning('请输入威胁样本 id（可在 v4.6 页详情查看）')
    api.v47.importIoc(importSample.trim())
      .then((r) => {
        message.success(`已抽取并入库 ${r.imported} 个 IOC`)
        loadAll()
        loadIoc()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const onSetDeter = (v: AnyRec) => {
    api.v47.setDeter({ ...v, operator: 'admin' })
      .then(() => {
        message.success('处置策略已保存')
        loadAll()
      })
      .catch((e: Error) => message.error(e.message))
  }
  const [resolveFamily, setResolveFamily] = useState('')
  const [resolve, setResolve] = useState<AnyRec | null>(null)

  return (
    <div style={{ maxWidth: 1180 }}>
      <Title level={3} style={{ marginTop: 0 }}>威胁情报运营中台 · v4.7</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        家族可视化 / 谱系研判 · 主动威慑分级处置 · IOC 中心化检索与订阅
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Tabs
        items={[
          { key: 'family', label: '家族谱系', children: familyTab() },
          { key: 'deter', label: '主动威慑', children: deterTab() },
          { key: 'ioc', label: 'IOC 中心', children: iocTab() },
        ]}
      />
    </div>
  )

  function familyTab() {
    return (
      <Row gutter={[12, 12]}>
        <Col span={8}><Card><Statistic title="聚合家族数" value={families.length} /></Card></Col>
        <Col span={8}><Card><Statistic title="谱系连边" value={graph?.links?.length ?? 0} /></Card></Col>
        <Col span={8}><Card><Statistic title="家族样本总数" value={families.reduce((a: number, r: AnyRec) => a + Number(r.size ?? 0), 0)} /></Card></Col>
        <Col span={24}>
          <Card title="家族档案" style={{ border: '1px solid var(--border-strong)' }}>
            <Table rowKey={(r: AnyRec) => r.family} columns={familyColumns} dataSource={families} size="small" pagination={false} />
          </Card>
        </Col>
        <Col span={24}>
          <Card title="家族谱系（血缘）图" style={{ border: '1px solid var(--border-strong)' }}>
            <FamilyGraph graph={graph} />
          </Card>
        </Col>
      </Row>
    )
  }

  function deterTab() {
    const byAction = (deter?.by_action ?? {}) as AnyRec
    return (
      <Row gutter={[12, 12]}>
        {(['BLOCK', 'ISOLATE', 'MONITOR', 'IGNORE'] as const).map((a) => (
          <Col span={6} key={a}>
            <Card><Statistic title={`处置 · ${a}`} value={byAction[a] ?? 0} valueStyle={{ color: a === 'BLOCK' ? '#ff3b30' : undefined }} /></Card>
          </Col>
        ))}
        <Col span={24}>
          <Card title="处置策略（对已确认恶意样本/家族设置分级处置）" style={{ border: '1px solid var(--border-strong)' }}>
            <Form
              layout="inline" style={{ marginBottom: 14, rowGap: 10 }}
              onFinish={onSetDeter}
              initialValues={{ scopeType: 'FAMILY', action: 'BLOCK', severity: 4 }}
            >
              <Form.Item name="scopeType" label="作用域">
                <Select style={{ width: 130 }} options={[
                  { value: 'FAMILY', label: '家族' }, { value: 'SAMPLE', label: '样本' }, { value: 'SIGNATURE', label: '特征码' },
                ]} />
              </Form.Item>
              <Form.Item name="scopeValue" label="目标" rules={[{ required: true, message: '必填' }]}>
                <Input placeholder="如 CLUSTER_0 或样本 id" style={{ width: 200 }} />
              </Form.Item>
              <Form.Item name="action" label="处置">
                <Select style={{ width: 130 }} options={[
                  { value: 'BLOCK', label: '阻断' }, { value: 'ISOLATE', label: '隔离' },
                  { value: 'MONITOR', label: '观测' }, { value: 'IGNORE', label: '放行' },
                ]} />
              </Form.Item>
              <Form.Item name="severity" label="严重度">
                <InputNumber min={1} max={5} style={{ width: 90 }} />
              </Form.Item>
              <Form.Item name="note" label="备注">
                <Input placeholder="处置说明" style={{ width: 220 }} />
              </Form.Item>
              <Form.Item>
                <Button type="primary" htmlType="submit">设置策略</Button>
              </Form.Item>
            </Form>
            <div style={{ marginBottom: 14 }}>
              <span style={{ color: '#8b949e', fontSize: 13 }}>事件风控联动模拟：</span>
              <Input
                value={resolveFamily} onChange={(e) => setResolveFamily(e.target.value)}
                placeholder="输入命中家族（如 CLUSTER_0）"
                style={{ width: 220, marginLeft: 8 }}
              />
              <Button style={{ marginLeft: 6 }} onClick={() => api.v47.resolveDeter(resolveFamily).then(setResolve)}>解析处置</Button>
              {resolve && (
                <div style={{ marginTop: 8, fontSize: 13 }}>
                  命中 <Tag color="purple">{resolve.family}</Tag>
                  → <Tag color={deterColor[resolve.action] ?? 'default'}>{resolve.action}</Tag>
                  （严重度 {resolve.severity} · {resolve.protected ? '已受处置策略保护' : '默认观测'})
                  <Button size="small" style={{ marginLeft: 8 }} onClick={() => api.v47.resolveDeter(resolveFamily).then(setResolve)}>再试</Button>
                </div>
              )}
            </div>
            <Table rowKey={(r: AnyRec) => r.id} columns={deterColumns} dataSource={deterPolicies} size="small" pagination={false} />
          </Card>
        </Col>
      </Row>
    )
  }

  function iocTab() {
    return (
      <Row gutter={[12, 12]}>
        <Col span={4}><Card><Statistic title="IOC 总数" value={ioc?.total ?? 0} /></Card></Col>
        <Col span={4}><Card><Statistic title="开启" value={ioc?.open ?? 0} /></Card></Col>
        <Col span={4}><Card><Statistic title="已出示" value={ioc?.disarmed ?? 0} /></Card></Col>
        <Col span={4}><Card><Statistic title="订阅告警" value={ioc?.subscribed ?? 0} /></Card></Col>
        <Col span={8}><Card><Statistic title="高严重度（≥4）" value={ioc?.high_severity ?? 0} /></Card></Col>

        <Col span={24}>
          <Card title="IOC 中心化库" style={{ border: '1px solid var(--border-strong)' }}>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
              <Input
                allowClear placeholder="检索值 / 家族 / 来源" style={{ width: 220 }}
                onChange={(e) => setQuery((p) => ({ ...p, q: e.target.value }))}
              />
              <Select
                allowClear placeholder="类型" style={{ width: 140 }}
                onChange={(v) => setQuery((p) => ({ ...p, type: v ?? '' }))}
                options={['FILE_HASH', 'STRING', 'IP', 'URL', 'CLIENT_FAMILY'].map((t) => ({ value: t, label: t }))}
              />
              <Select
                allowClear placeholder="状态" style={{ width: 130 }}
                onChange={(v) => setQuery((p) => ({ ...p, state: v ?? '' }))}
                options={['OPEN', 'DISARMED'].map((s) => ({ value: s, label: s }))}
              />
              <Input
                placeholder="源样本 id（导入用）" value={importSample}
                onChange={(e) => setImportSample(e.target.value)} style={{ width: 240 }}
              />
              <Button type="primary" onClick={doImport}>从样本抽取入库</Button>
              <Button onClick={loadIoc}>刷新</Button>
            </div>
            <Table
              rowKey={(r: AnyRec) => r.id} columns={iocColumns} dataSource={iocItems} size="small"
              pagination={{ pageSize: Number(query.size), total: Number(iocList?.total ?? 0), showSizeChanger: false,
                onChange: (p) => setQuery((q) => ({ ...q, page: p - 1 })) }}
            />
          </Card>
        </Col>
      </Row>
    )
  }
}