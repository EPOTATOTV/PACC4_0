import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Drawer, Input, Modal, Select, Space, Table, Tag, Tooltip, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { CopyOutlined, PlusOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import { api } from '../../api/client'
import type { V54KeyAuditRow, V54ManagedKey, V54ManagedKeyList } from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

const STATE_META: Record<string, { label: string; color: string }> = {
  ACTIVE: { label: '生效', color: 'green' },
  ROTATED: { label: '已轮换', color: 'blue' },
  EXPIRED: { label: '已过期', color: 'default' },
  REVOKED: { label: '已吊销', color: 'red' },
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

/** 后端轮换 / 吊销冲突返回 409 + {"error":...}，request 会把它整体当错误信息抛出，这里还原为可读文案。 */
function extractError(e: unknown) {
  const raw = e instanceof Error ? e.message : String(e)
  try {
    const parsed = JSON.parse(raw) as { error?: string; message?: string }
    return parsed.error || parsed.message || raw
  } catch {
    return raw
  }
}

export default function KeyManagement() {
  const [data, setData] = useState<V54ManagedKeyList | null>(null)
  const [audit, setAudit] = useState<{ ok: boolean; checked: number } | null>(null)
  const [auditRows, setAuditRows] = useState<V54KeyAuditRow[]>([])
  const [loading, setLoading] = useState(false)
  const [auditLoading, setAuditLoading] = useState(false)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState('')

  const [createOpen, setCreateOpen] = useState(false)
  const [createPurpose, setCreatePurpose] = useState('')
  const [createNote, setCreateNote] = useState('')

  const [rotateTarget, setRotateTarget] = useState<V54ManagedKey | null>(null)
  const [rotateNote, setRotateNote] = useState('')

  const [revokeTarget, setRevokeTarget] = useState<V54ManagedKey | null>(null)
  const [revokeReason, setRevokeReason] = useState('')

  const [auditTarget, setAuditTarget] = useState<V54ManagedKey | null>(null)
  const [keyAuditRows, setKeyAuditRows] = useState<V54KeyAuditRow[]>([])
  const [keyAuditLoading, setKeyAuditLoading] = useState(false)

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })
  const revealModal = useModalReveal()

  const loadKeys = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.v54.keys.list()
      setData(d)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  const loadAudit = useCallback(async () => {
    setAuditLoading(true)
    try {
      const d = await api.v54.keys.audit()
      setAuditRows(d.items ?? [])
      setAudit({ ok: d.chain.ok, checked: d.chain.checked })
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setAuditLoading(false)
    }
  }, [])

  useEffect(() => { void loadKeys() }, [loadKeys])
  useEffect(() => { void loadAudit() }, [loadAudit])

  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [data, reveal])

  useEffect(() => {
    if (!createOpen && !rotateTarget && !revokeTarget) return
    const id = requestAnimationFrame(revealModal)
    return () => cancelAnimationFrame(id)
  }, [createOpen, rotateTarget, revokeTarget, revealModal])

  const counts = useMemo(() => {
    const by = data?.by_state ?? {}
    return {
      total: data?.items.length ?? 0,
      active: by.ACTIVE ?? 0,
      rotated: by.ROTATED ?? 0,
      revoked: by.REVOKED ?? 0,
    }
  }, [data])

  async function submitCreate() {
    if (!createPurpose) {
      message.error('请选择密钥用途')
      return
    }
    setBusy('create')
    try {
      await api.v54.keys.create(createPurpose, createNote)
      message.success('密钥已创建')
      setCreateOpen(false)
      setCreateNote('')
      setCreatePurpose('')
      await loadKeys()
      await loadAudit()
    } catch (e) {
      message.error(extractError(e))
    } finally {
      setBusy('')
    }
  }

  async function submitRotate() {
    if (!rotateTarget) return
    setBusy('rotate')
    try {
      await api.v54.keys.rotate(rotateTarget.key_id, rotateNote)
      message.success(`密钥 ${rotateTarget.key_id} 已轮换`)
      setRotateTarget(null)
      setRotateNote('')
      await loadKeys()
      await loadAudit()
    } catch (e) {
      message.error(extractError(e))
    } finally {
      setBusy('')
    }
  }

  async function submitRevoke() {
    if (!revokeTarget) return
    if (!revokeReason.trim()) {
      message.error('吊销必须填写原因')
      return
    }
    setBusy('revoke')
    try {
      await api.v54.keys.revoke(revokeTarget.key_id, revokeReason.trim())
      message.success(`密钥 ${revokeTarget.key_id} 已吊销`)
      setRevokeTarget(null)
      setRevokeReason('')
      await loadKeys()
      await loadAudit()
    } catch (e) {
      message.error(extractError(e))
    } finally {
      setBusy('')
    }
  }

  function openKeyAudit(row: V54ManagedKey) {
    setAuditTarget(row)
    setKeyAuditRows([])
    setKeyAuditLoading(true)
    api.v54.keys.keyAudit(row.key_id)
      .then((d) => setKeyAuditRows(d.items ?? []))
      .catch((e) => message.error(extractError(e)))
      .finally(() => setKeyAuditLoading(false))
  }

  const columns: TableColumnsType<V54ManagedKey> = [
    { title: '密钥 ID', dataIndex: 'key_id', width: 210, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '用途', dataIndex: 'purpose', width: 140, render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: '算法', dataIndex: 'algorithm', width: 110 },
    {
      title: '状态', dataIndex: 'state', width: 96,
      render: (v: string) => <Tag color={STATE_META[v]?.color ?? 'default'}>{STATE_META[v]?.label ?? v}</Tag>,
    },
    { title: '版本', dataIndex: 'version', width: 72 },
    { title: '派生来源', dataIndex: 'derived_from', width: 150, render: (v: string) => v || '-' },
    {
      title: '指纹', dataIndex: 'fingerprint', width: 190,
      render: (v: string) => (
        <Space size={4}>
          <Tooltip title={v}><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span></Tooltip>
          <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => void copy(v, '密钥指纹')} />
        </Space>
      ),
    },
    { title: '创建人', dataIndex: 'created_by', width: 120, render: (v: string) => v || '-' },
    { title: '创建时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    { title: '到期时间', dataIndex: 'expires_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '操作', width: 220, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Tooltip title={r.state === 'REVOKED' ? '已吊销密钥不可轮换' : '生成新版本并保留旧密钥解密能力'}>
            <Button
              size="small"
              disabled={r.state === 'REVOKED'}
              onClick={() => { setRotateTarget(r); setRotateNote('') }}
            >
              轮换
            </Button>
          </Tooltip>
          <Button
            size="small"
            danger
            disabled={r.state === 'REVOKED'}
            onClick={() => { setRevokeTarget(r); setRevokeReason('') }}
          >
            吊销
          </Button>
          <Button size="small" type="text" onClick={() => openKeyAudit(r)}>审计</Button>
        </Space>
      ),
    },
  ]

  const auditColumns: TableColumnsType<V54KeyAuditRow> = [
    { title: '序号', width: 72, render: (_, __, i) => i + 1 },
    { title: '密钥 ID', dataIndex: 'key_id', width: 200, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '动作', dataIndex: 'action', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '状态迁移', width: 180,
      render: (_, r) => (
        <span style={{ fontSize: 12 }}>
          {r.from_state ? `${STATE_META[r.from_state]?.label ?? r.from_state} → ` : ''}
          {STATE_META[r.to_state]?.label ?? r.to_state ?? '-'}
        </span>
      ),
    },
    { title: '操作人', dataIndex: 'operator', width: 120, render: (v: string) => v || '-' },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: (v: string) => v || '-' },
    { title: '时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '哈希', dataIndex: 'hash', width: 170,
      render: (v: string) => <Tooltip title={v}><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span></Tooltip>,
    },
  ]

  const keyAuditColumns: TableColumnsType<V54KeyAuditRow> = [
    { title: '时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    { title: '动作', dataIndex: 'action', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '状态迁移', width: 180,
      render: (_, r) => (
        <span style={{ fontSize: 12 }}>
          {r.from_state ? `${STATE_META[r.from_state]?.label ?? r.from_state} → ` : ''}
          {STATE_META[r.to_state]?.label ?? r.to_state ?? '-'}
        </span>
      ),
    },
    { title: '操作人', dataIndex: 'operator', width: 120, render: (v: string) => v || '-' },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: (v: string) => v || '-' },
  ]

  return (
    <div>
      <PageHeader
        title="密钥管理"
        description="密钥层级、派生指纹与轮换生命周期，含逐条审计哈希链"
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <Space>
            <Button icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建密钥</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => { void loadKeys(); void loadAudit() }}>刷新</Button>
          </Space>
        }
      />

      {data && !data.root_configured && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 15 }}
          message="未配置 PACC_SECURITY_KEY_ROOT，当前回退使用 JWT 密钥派生"
          description="生产环境必须独立配置根密钥：回退派生会使所有用途共享同一根，任一用途泄露即危及其余密钥。"
        />
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="密钥总数" value={counts.total} hint={`轮换周期 ${data?.rotation_days ?? 0} 天`} />
        <MetricCard label="ACTIVE" value={counts.active} accent="#52c41a" />
        <MetricCard label="ROTATED" value={counts.rotated} accent="#4096ff" />
        <MetricCard label="REVOKED" value={counts.revoked} accent="#ff4d3d" />
      </div>

      <Card styles={{ body: { padding: 0 } }} className="pacc-glass-md" style={{ marginBottom: 15 }}>
        <div style={{ padding: '12px 14px 0', fontSize: 13, fontWeight: 600 }}>密钥列表</div>
        <div ref={tableRef}>
          <Table<V54ManagedKey>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={data?.items ?? []}
            loading={loading}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            scroll={{ x: 1820 }}
            locale={{ emptyText: '暂无托管密钥，可先新建密钥' }}
          />
        </div>
      </Card>

      <Card
        title="审计日志"
        className="pacc-glass-md"
        extra={
          <Space size={10}>
            <span style={{ fontSize: 12, color: audit?.ok ? '#52c41a' : '#ff4d3d' }}>
              链状态 {audit ? (audit.ok ? '正常' : '异常') : '-'}
            </span>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>已校验 {audit?.checked ?? 0} 条</span>
            <Button size="small" icon={<SafetyOutlined />} loading={auditLoading} onClick={() => void loadAudit()}>校验</Button>
          </Space>
        }
      >
        <Table<V54KeyAuditRow>
          rowKey="id"
          size="small"
          columns={auditColumns}
          dataSource={auditRows}
          loading={auditLoading}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 1180 }}
          locale={{ emptyText: '暂无密钥操作审计' }}
        />
      </Card>

      <Modal
        title="新建密钥"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void submitCreate()}
        okText="创建"
        confirmLoading={busy === 'create'}
        destroyOnClose
      >
        <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr', rowGap: 13, alignItems: 'center' }}>
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>用途</span>
          <Select
            value={createPurpose || undefined}
            onChange={setCreatePurpose}
            placeholder="选择密钥用途"
            options={(data?.purposes ?? []).map((p) => ({ label: p, value: p }))}
          />
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>备注</span>
          <Input
            value={createNote}
            onChange={(e) => setCreateNote(e.target.value)}
            placeholder="可选，说明用途或申请单号"
          />
        </div>
        <p style={{ color: 'var(--muted)', fontSize: 11.5, marginTop: 14, marginBottom: 0 }}>
          密钥由根密钥分层派生，不落明文；创建后自动进入 ACTIVE 状态并登记审计。
        </p>
      </Modal>

      <Modal
        title={`轮换密钥 · ${rotateTarget?.key_id ?? ''}`}
        open={!!rotateTarget}
        onCancel={() => setRotateTarget(null)}
        onOk={() => void submitRotate()}
        okText="确认轮换"
        confirmLoading={busy === 'rotate'}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 13 }}
          message="轮换后旧版本密钥不会删除"
          description="已加密的历史密文仍可通过已轮换密钥解密；新写入统一使用新版本。"
        />
        <Input
          value={rotateNote}
          onChange={(e) => setRotateNote(e.target.value)}
          placeholder="轮换备注（可选）"
        />
      </Modal>

      <Modal
        title={`吊销密钥 · ${revokeTarget?.key_id ?? ''}`}
        open={!!revokeTarget}
        onCancel={() => setRevokeTarget(null)}
        onOk={() => void submitRevoke()}
        okText="确认吊销"
        okButtonProps={{ danger: true }}
        confirmLoading={busy === 'revoke'}
        destroyOnClose
      >
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 13 }}
          message="吊销不可逆"
          description="吊销后该密钥立即失效，使用该密钥加密的数据将无法解密，请确认已完成迁移。"
        />
        <Input.TextArea
          rows={3}
          value={revokeReason}
          onChange={(e) => setRevokeReason(e.target.value)}
          placeholder="吊销原因（必填，记入审计）"
        />
      </Modal>

      <Drawer title={`密钥审计 · ${auditTarget?.key_id ?? ''}`} open={!!auditTarget} onClose={() => setAuditTarget(null)} width={720}>
        <Table<V54KeyAuditRow>
          rowKey="id"
          size="small"
          columns={keyAuditColumns}
          dataSource={keyAuditRows}
          loading={keyAuditLoading}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 760 }}
          locale={{ emptyText: '该密钥暂无操作记录' }}
        />
      </Drawer>
    </div>
  )
}