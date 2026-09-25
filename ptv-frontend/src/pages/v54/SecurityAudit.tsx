import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Drawer, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Tooltip, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { CopyOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import { api } from '../../api/client'
import type {
  V54AttestationList, V54AttestationRow, V54KnownHash, V54SecurityChain, V54SecurityEvent, V54SecurityOverview,
} from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

// 已知的安全事件类型：与后端 SecurityEvent.Type 枚举逐字一致，作筛选下拉的静态兜底
// （后端再按真实分布合并）。写成后端存储的大写形式——后端 parseType 对未知值会静默退到
// INTEGRITY_VIOLATION，这里若写不存在或大小写不符的值，筛出来的会是错的那一类事件。
const KNOWN_EVENT_TYPES = [
  'DEBUGGER_DETECTED', 'HOOK_DETECTED', 'INTEGRITY_VIOLATION', 'MEMORY_TAMPER',
  'VM_DETECTED', 'SANDBOX_DETECTED', 'FRIDA_DETECTED', 'CHEAT_ENGINE_DETECTED',
  'UNAUTHORIZED_ACCESS', 'KEY_ROTATION', 'MODEL_UPDATE', 'CONFIG_CHANGE',
  'ATTESTATION_PASS', 'ATTESTATION_FAIL',
]
// 等级取值同后端 SecurityEvent.Level；不存在 LOW，误传会被 parseLevel 退到 MEDIUM。
const LEVELS = ['CRITICAL', 'HIGH', 'MEDIUM', 'INFO']
const HASH_KINDS = ['CODE_SEGMENT', 'CONFIG', 'JAR']

const LEVEL_COLOR: Record<string, string> = {
  CRITICAL: 'red', HIGH: 'orange', MEDIUM: 'gold', LOW: 'blue', INFO: 'default',
}

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

function shortHash(hash: string) {
  return hash.length > 18 ? `${hash.slice(0, 10)}…${hash.slice(-6)}` : hash
}

async function copy(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(`已复制${what}`)
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

/** 证据为后端存储的原始串，多为 JSON 文本；解析失败时原样展示。 */
function prettyEvidence(raw: string) {
  if (!raw) return '（无证据内容）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

export default function SecurityAudit() {
  const [overview, setOverview] = useState<V54SecurityOverview | null>(null)
  const [attestation, setAttestation] = useState<V54AttestationList | null>(null)
  const [chain, setChain] = useState<V54SecurityChain | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const [chainChecking, setChainChecking] = useState(false)

  const [events, setEvents] = useState<V54SecurityEvent[]>([])
  const [eventsTotal, setEventsTotal] = useState(0)
  const [loadingEvents, setLoadingEvents] = useState(false)
  const [level, setLevel] = useState('')
  const [type, setType] = useState('')
  const [pteid, setPteid] = useState('')
  const [appliedPteid, setAppliedPteid] = useState('')
  const [limit, setLimit] = useState(100)

  const [evidence, setEvidence] = useState<V54SecurityEvent | null>(null)

  const [hashes, setHashes] = useState<V54KnownHash[]>([])
  const [hashBusy, setHashBusy] = useState('')
  const [hashModal, setHashModal] = useState(false)
  const [hashForm, setHashForm] = useState({ label: '', kind: 'CODE_SEGMENT', hash: '', active: true })
  const [hashSaving, setHashSaving] = useState(false)

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })
  const revealModal = useModalReveal()

  const loadOverview = useCallback(async () => {
    setLoading(true)
    try {
      const [o, a] = await Promise.all([
        api.v54.security.overview(24),
        api.v54.security.attestation(undefined, 50),
      ])
      setOverview(o)
      setAttestation(a)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  const loadEvents = useCallback(async () => {
    setLoadingEvents(true)
    try {
      const d = await api.v54.security.events({
        level: level || undefined,
        type: type || undefined,
        pteid: appliedPteid || undefined,
        limit,
      })
      setEvents(d.items ?? [])
      setEventsTotal(d.total ?? 0)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoadingEvents(false)
    }
  }, [level, type, appliedPteid, limit])

  const loadHashes = useCallback(async () => {
    try {
      const d = await api.v54.security.hashes()
      setHashes(d.items ?? [])
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => { void loadOverview() }, [loadOverview])
  useEffect(() => { void loadEvents() }, [loadEvents])
  useEffect(() => { void loadHashes() }, [loadHashes])

  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [events, reveal])

  useEffect(() => {
    if (!evidence && !hashModal) return
    const id = requestAnimationFrame(revealModal)
    return () => cancelAnimationFrame(id)
  }, [evidence, hashModal, revealModal])

  // 概览里的链状态作初始值，手动「重新校验」后以校验结果为准
  const chainInfo = useMemo<V54SecurityChain | null>(() => {
    if (chain) return chain
    if (!overview) return null
    const c = overview.cards
    return { ok: c.chain_ok, checked: c.chain_checked, brokenAtSeq: c.chain_broken_at, detail: '' }
  }, [chain, overview])

  const brokenSeq = chainInfo ? (chainInfo.brokenAtSeq ?? chainInfo.broken_at ?? 0) : 0

  async function verifyChain() {
    setChainChecking(true)
    try {
      const c = await api.v54.security.chainVerify(500)
      setChain(c)
      message.success(c.ok ? '哈希链校验通过' : '哈希链校验发现断裂')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setChainChecking(false)
    }
  }

  async function deactivateHash(id: string) {
    setHashBusy(id)
    try {
      await api.v54.security.deactivateHash(id)
      message.success('已停用该哈希')
      await loadHashes()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setHashBusy('')
    }
  }

  async function submitHash() {
    const label = hashForm.label.trim()
    const hash = hashForm.hash.trim()
    if (!label) {
      message.error('请填写标签')
      return
    }
    if (!/^[0-9a-fA-F]{64}$/.test(hash)) {
      message.error('哈希需为 64 位十六进制字符')
      return
    }
    setHashSaving(true)
    try {
      await api.v54.security.addHash({ label, kind: hashForm.kind, hash: hash.toLowerCase(), active: hashForm.active })
      message.success('已登记已知良好哈希')
      setHashModal(false)
      setHashForm({ label: '', kind: 'CODE_SEGMENT', hash: '', active: true })
      await loadHashes()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setHashSaving(false)
    }
  }

  function refreshAll() {
    void loadOverview()
    void loadEvents()
    void loadHashes()
  }

  const typeOptions = useMemo(() => {
    const fromCards = (overview?.cards.by_type ?? []).map((t) => t.type)
    return Array.from(new Set([...fromCards, ...KNOWN_EVENT_TYPES]))
  }, [overview])

  const eventColumns: TableColumnsType<V54SecurityEvent> = [
    { title: '时间', dataIndex: 'occurred_at', width: 170, render: (v: string) => fmt(v) },
    { title: 'PTEID', dataIndex: 'pteid', width: 190, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '事件类型', dataIndex: 'event_type', width: 170, render: (v: string) => <Tag color="volcano">{v}</Tag> },
    {
      title: '级别', dataIndex: 'level', width: 90,
      render: (v: string) => <Tag color={LEVEL_COLOR[v] ?? 'default'}>{v}</Tag>,
    },
    { title: '平台', dataIndex: 'platform', width: 84 },
    { title: '客户端版本', dataIndex: 'client_version', width: 100 },
    { title: '详情', dataIndex: 'detail', ellipsis: true },
    {
      title: '证据', width: 84,
      render: (_, r) => <Button size="small" type="text" onClick={() => setEvidence(r)}>查看</Button>,
    },
    { title: '序号', dataIndex: 'seq', width: 78 },
    {
      title: '哈希', dataIndex: 'hash', width: 190,
      render: (v: string) => (
        <Space size={4}>
          <Tooltip title={v}>
            <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span>
          </Tooltip>
          <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => void copy(v, '事件哈希')} />
        </Space>
      ),
    },
  ]

  const attestationColumns: TableColumnsType<V54AttestationRow> = [
    { title: '时间', dataIndex: 'issued_at', width: 170, render: (v: string, r) => fmt(v || r.created_at) },
    { title: 'PTEID', dataIndex: 'pteid', width: 190, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '平台', dataIndex: 'platform', width: 90 },
    { title: '客户端版本', dataIndex: 'client_version', width: 100 },
    {
      title: '结果', dataIndex: 'status', width: 90,
      render: (v: string) => (v === 'PASS' ? <Tag color="green">PASS</Tag> : <Tag color="red">FAIL</Tag>),
    },
    { title: '原因', dataIndex: 'reason', ellipsis: true, render: (v: string) => v || '-' },
    { title: '耗时 ms', dataIndex: 'elapsed_ms', width: 92 },
    {
      title: 'code_hash', dataIndex: 'code_hash', width: 170,
      render: (v: string) => <Tooltip title={v}><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span></Tooltip>,
    },
  ]

  const hashColumns: TableColumnsType<V54KnownHash> = [
    { title: '标签', dataIndex: 'label', width: 190 },
    { title: '类型', dataIndex: 'kind', width: 130, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '哈希', dataIndex: 'hash', width: 200,
      render: (v: string) => (
        <Space size={4}>
          <Tooltip title={v}><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span></Tooltip>
          <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => void copy(v, '已知哈希')} />
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'active', width: 88, render: (v: boolean) => (v ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>) },
    { title: '创建人', dataIndex: 'created_by', width: 130, render: (v: string) => v || '-' },
    { title: '创建时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '操作', width: 96, fixed: 'right',
      render: (_, r) => (
        <Popconfirm
          title="确认停用该已知良好哈希？"
          description="停用后客户端上报该哈希将不再被信任。"
          okButtonProps={{ danger: true }}
          onConfirm={() => void deactivateHash(r.id)}
        >
          <Button size="small" type="text" danger disabled={!r.active || hashBusy === r.id}>停用</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="安全审计"
        description="客户端上报的安全事件与远程证明记录，附审计哈希链完整性校验"
        error={err}
        onCloseError={() => setErr('')}
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="事件总数" value={overview?.cards.total ?? 0} hint="窗口内累计安全事件" />
        <MetricCard label="CRITICAL" value={overview?.cards.critical ?? 0} accent="#ff4d3d" />
        <MetricCard label="HIGH" value={overview?.cards.high ?? 0} accent="#ffa940" />
        <MetricCard label="证明通过率" value={`${((attestation?.pass_rate ?? 0) * 100).toFixed(2)} %`} hint={`样本 ${attestation?.total ?? 0} 条`} />
        <MetricCard
          label="审计链状态"
          value={chainInfo ? (chainInfo.ok ? '正常' : '异常') : '-'}
          accent={chainInfo?.ok ? '#52c41a' : '#ff4d3d'}
          hint={chainInfo ? `已校验 ${chainInfo.checked} 条` : undefined}
        />
      </div>

      {chainInfo && !chainInfo.ok && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 15 }}
          message={`哈希链在第 ${brokenSeq} 条断裂，可能存在日志删改`}
          description={
            <Space wrap>
              <span>{chainInfo.detail || '请核查该序号附近的安全事件记录，确认是否有人为删改。'}</span>
              <Button size="small" loading={chainChecking} onClick={() => void verifyChain()}>重新校验</Button>
            </Space>
          }
        />
      )}

      <Card
        title="安全事件"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>命中 {eventsTotal} 条</span>}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 13, flexWrap: 'wrap' }}>
          <Select
            allowClear
            placeholder="全部级别"
            value={level || undefined}
            onChange={(v) => setLevel(v ?? '')}
            style={{ width: 132 }}
            options={LEVELS.map((l) => ({ label: l, value: l }))}
          />
          <Select
            allowClear
            showSearch
            placeholder="全部事件类型"
            value={type || undefined}
            onChange={(v) => setType(v ?? '')}
            style={{ width: 190 }}
            options={typeOptions.map((t) => ({ label: t, value: t }))}
          />
          <Input
            placeholder="按 PTEID 过滤"
            value={pteid}
            onChange={(e) => setPteid(e.target.value)}
            onPressEnter={() => setAppliedPteid(pteid.trim())}
            style={{ width: 200 }}
            allowClear
          />
          <Button icon={<SearchOutlined />} onClick={() => setAppliedPteid(pteid.trim())}>筛选</Button>
          <Select
            value={limit}
            onChange={setLimit}
            style={{ width: 108 }}
            options={[50, 100, 200, 500].map((n) => ({ label: `最近 ${n}`, value: n }))}
          />
        </div>
        <div ref={tableRef}>
          <Table<V54SecurityEvent>
            rowKey="id"
            size="small"
            columns={eventColumns}
            dataSource={events}
            loading={loadingEvents}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            scroll={{ x: 1420 }}
            locale={{ emptyText: '暂无符合条件的安全事件' }}
          />
        </div>
      </Card>

      <Card
        title="远程证明记录"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>通过率 {((attestation?.pass_rate ?? 0) * 100).toFixed(2)}%</span>}
      >
        <Table<V54AttestationRow>
          rowKey="id"
          size="small"
          columns={attestationColumns}
          dataSource={attestation?.items ?? []}
          pagination={{ pageSize: 8, showSizeChanger: false }}
          scroll={{ x: 1180 }}
          locale={{ emptyText: '暂无远程证明记录' }}
        />
      </Card>

      <Card
        title="已知良好哈希"
        className="pacc-glass-md"
        extra={
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setHashModal(true)}>新增</Button>
        }
      >
        <Table<V54KnownHash>
          rowKey="id"
          size="small"
          columns={hashColumns}
          dataSource={hashes}
          pagination={{ pageSize: 8, showSizeChanger: false }}
          scroll={{ x: 1080 }}
          locale={{ emptyText: '暂无已知良好哈希，请先登记代码段 / 配置基线' }}
        />
      </Card>

      <Drawer title="安全事件证据" open={!!evidence} onClose={() => setEvidence(null)} width={560}>
        {evidence && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: '104px 1fr', rowGap: 11, fontSize: 12.5, marginBottom: 14 }}>
              <span style={{ color: 'var(--muted)' }}>事件类型</span>
              <span>{evidence.event_type}</span>
              <span style={{ color: 'var(--muted)' }}>PTEID</span>
              <span style={{ fontFamily: 'monospace' }}>{evidence.pteid}</span>
              <span style={{ color: 'var(--muted)' }}>发生时间</span>
              <span>{fmt(evidence.occurred_at)}</span>
              <span style={{ color: 'var(--muted)' }}>序号</span>
              <span>{evidence.seq}</span>
              <span style={{ color: 'var(--muted)' }}>事件哈希</span>
              <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {evidence.hash}
                <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => void copy(evidence.hash, '事件哈希')} />
              </span>
              <span style={{ color: 'var(--muted)' }}>前序哈希</span>
              <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{evidence.prev_hash || '-'}</span>
            </div>
            <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>证据明细</div>
            <pre style={{
              margin: 0, padding: 12, fontSize: 12, lineHeight: 1.7, borderRadius: 4,
              background: 'rgba(255,255,255,.03)', border: '1px solid var(--border)',
              fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 420, overflow: 'auto',
            }}>
              {prettyEvidence(evidence.evidence)}
            </pre>
          </>
        )}
      </Drawer>

      <Modal
        title="新增已知良好哈希"
        open={hashModal}
        onCancel={() => setHashModal(false)}
        onOk={() => void submitHash()}
        okText="登记"
        confirmLoading={hashSaving}
        destroyOnClose
      >
        <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr', rowGap: 13, alignItems: 'center' }}>
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>标签</span>
          <Input
            value={hashForm.label}
            onChange={(e) => setHashForm((f) => ({ ...f, label: e.target.value }))}
            placeholder="如 PACC 5.4 主程序代码段"
          />
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>类型</span>
          <Select
            value={hashForm.kind}
            onChange={(v) => setHashForm((f) => ({ ...f, kind: v }))}
            options={HASH_KINDS.map((k) => ({ label: k, value: k }))}
          />
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>哈希</span>
          <Input
            value={hashForm.hash}
            onChange={(e) => setHashForm((f) => ({ ...f, hash: e.target.value }))}
            placeholder="64 位十六进制 SHA-256"
          />
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>启用</span>
          <Switch checked={hashForm.active} onChange={(v) => setHashForm((f) => ({ ...f, active: v }))} />
        </div>
        <p style={{ color: 'var(--muted)', fontSize: 11.5, marginTop: 14, marginBottom: 0 }}>
          登记后会参与客户端完整性比对；提交前会在本地校验哈希为 64 位十六进制字符。
        </p>
      </Modal>
    </div>
  )
}