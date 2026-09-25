import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Drawer, Input, Switch, Table, Tag, Tooltip, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { CopyOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import { api } from '../../api/client'
import type { V53DeviceFingerprintRow } from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

function fmt(ts: string) {
  return ts ? ts.replace('T', ' ').slice(0, 19) : '-'
}

function shortHash(hash: string) {
  return hash.length > 18 ? `${hash.slice(0, 10)}…${hash.slice(-6)}` : hash
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制完整指纹摘要')
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

export default function DeviceFingerprint() {
  const [rows, setRows] = useState<V53DeviceFingerprintRow[]>([])
  const [meta, setMeta] = useState({ shared_total: 0, mutation_total: 0, truncated: false, listed: 0 })
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const [pteid, setPteid] = useState('')
  const [applied, setApplied] = useState('')
  const [sharedOnly, setSharedOnly] = useState(false)
  const [detail, setDetail] = useState<V53DeviceFingerprintRow | null>(null)

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })
  const revealModal = useModalReveal()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.v53.devices(applied || undefined, sharedOnly)
      setRows(d.items ?? [])
      setMeta({ shared_total: d.shared_total, mutation_total: d.mutation_total, truncated: d.truncated, listed: d.listed })
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [applied, sharedOnly])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [rows, reveal])

  useEffect(() => {
    if (!detail) return
    const id = requestAnimationFrame(revealModal)
    return () => cancelAnimationFrame(id)
  }, [detail, revealModal])

  const stats = useMemo(() => {
    const accounts = new Set(rows.map((r) => r.pteid))
    return { accounts: accounts.size, fingerprints: new Set(rows.map((r) => r.fingerprint_hash)).size }
  }, [rows])

  const columns: TableColumnsType<V53DeviceFingerprintRow> = [
    {
      title: '指纹摘要', dataIndex: 'fingerprint_hash', width: 210,
      render: (v: string) => (
        <Tooltip title={v}>
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{shortHash(v)}</span>
        </Tooltip>
      ),
    },
    {
      title: '关联 PTEID', dataIndex: 'pteid', width: 190,
      render: (v: string, r) => (
        <div style={{ lineHeight: 1.5 }}>
          <Link to={`/v52/profile?pteid=${encodeURIComponent(v)}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</Link>
          {r.shared_account && (
            <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>另见 {r.shared_pteids.filter((p) => p !== v).join('、')}</div>
          )}
        </div>
      ),
    },
    {
      title: '共享账号', dataIndex: 'shared_count', width: 100,
      render: (v: number, r) => (
        <Tooltip title={r.shared_pteids.join('、')}>
          <Tag color={v > 1 ? 'red' : 'default'}>{v} 个</Tag>
        </Tooltip>
      ),
    },
    {
      title: '标记', width: 150,
      render: (_, r) => (
        <>
          {r.mutation && <Tag color="volcano">设备突变</Tag>}
          {r.shared_account && <Tag color="red">多账号共用</Tag>}
          {!r.mutation && !r.shared_account && <span style={{ color: 'var(--muted)', fontSize: 12 }}>正常</span>}
        </>
      ),
    },
    { title: '最近登录平台', dataIndex: 'platform', width: 120, render: (v: string) => v || '-' },
    { title: '出现次数', dataIndex: 'seen_count', width: 92 },
    { title: '首次出现', dataIndex: 'first_seen_at', width: 170, render: (v: string) => fmt(v) },
    { title: '最后出现', dataIndex: 'last_seen_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '操作', width: 130, fixed: 'right',
      render: (_, r) => (
        <Button size="small" type="text" onClick={() => setDetail(r)}>明细</Button>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="硬件指纹管理"
        description="客户端只上报指纹摘要（服务端不收明文），同一指纹被多个账号使用即为共享设备信号"
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <>
            <Input
              placeholder="按 PTEID 过滤"
              value={pteid}
              onChange={(e) => setPteid(e.target.value)}
              onPressEnter={() => setApplied(pteid.trim())}
              style={{ width: 200 }}
              allowClear
            />
            <Button icon={<SearchOutlined />} onClick={() => setApplied(pteid.trim())}>筛选</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
          </>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="列出指纹行" value={meta.listed} hint="单次查询上限 300 条" />
        <MetricCard label="不重复指纹" value={stats.fingerprints} />
        <MetricCard label="共享账号指纹" value={meta.shared_total} accent="#ff4d3d" hint="同一指纹 ≥2 个账号" />
        <MetricCard label="突变玩家" value={meta.mutation_total} accent="#ffa940" hint="过手 ≥2 枚指纹" />
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 13, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>只看多账号共用</span>
        <Switch size="small" checked={sharedOnly} onChange={setSharedOnly} />
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>涉及 {stats.accounts} 个账号</span>
        {meta.truncated && <Tag color="orange">已达 300 条上限，请用 PTEID 过滤缩小范围</Tag>}
      </div>

      <Card styles={{ body: { padding: 0 } }} className="pacc-glass-md">
        <div ref={tableRef}>
          <Table<V53DeviceFingerprintRow>
            rowKey={(r) => `${r.pteid}-${r.fingerprint_hash}`}
            columns={columns}
            dataSource={rows}
            loading={loading}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            scroll={{ x: 1300 }}
            locale={{ emptyText: '暂无指纹记录；客户端上报硬件摘要后自动登记' }}
          />
        </div>
      </Card>

      <Drawer title="指纹明细" open={!!detail} onClose={() => setDetail(null)} width={520}>
        {detail && (
          <>
            {detail.shared_account && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 13 }}
                message={`该指纹被 ${detail.shared_count} 个账号使用`}
                description="共享设备常见于账号租借 / 代打 / 工作室；建议结合信誉分与检测记录人工复核。"
              />
            )}
            <div style={{ display: 'grid', gridTemplateColumns: '112px 1fr', rowGap: 11, fontSize: 12.5 }}>
              <span style={{ color: 'var(--muted)' }}>指纹摘要</span>
              <span style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {detail.fingerprint_hash}
                <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => void copy(detail.fingerprint_hash)} />
              </span>
              <span style={{ color: 'var(--muted)' }}>关联 PTEID</span>
              <span style={{ fontFamily: 'monospace' }}>{detail.shared_pteids.join('、')}</span>
              <span style={{ color: 'var(--muted)' }}>共享账号数</span>
              <span>{detail.shared_count} 个</span>
              <span style={{ color: 'var(--muted)' }}>设备突变</span>
              <span>{detail.mutation ? '是（该玩家持有 ≥2 枚指纹）' : '否'}</span>
              <span style={{ color: 'var(--muted)' }}>最近登录平台</span>
              <span>{detail.platform || '未知'}</span>
              <span style={{ color: 'var(--muted)' }}>出现次数</span>
              <span>{detail.seen_count} 次</span>
              <span style={{ color: 'var(--muted)' }}>首次出现</span>
              <span>{fmt(detail.first_seen_at)}</span>
              <span style={{ color: 'var(--muted)' }}>最后出现</span>
              <span>{fmt(detail.last_seen_at)}</span>
            </div>
            <div style={{ marginTop: 16 }}>
              <Link to={`/v52/profile?pteid=${encodeURIComponent(detail.pteid)}`}>查看该玩家行为画像 →</Link>
            </div>
            <p style={{ color: 'var(--muted)', fontSize: 11.5, marginTop: 14 }}>
              平台字段取自该玩家的登录设备记录（t_device），与指纹摘要不是同一个值，仅作环境参考。
            </p>
          </>
        )}
      </Drawer>
    </div>
  )
}