import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Progress, Select, Space, Table, Tag, Tooltip, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import { api } from '../../api/client'
import type { TenantQuotaMetric, TenantQuotaRow, TenantUsageRow } from '../../types'
import { useTableRowReveal } from '../../hooks/useGSAP'

/**
 * DF §4.2.3 多租户配额与计量页。
 * 配额使用率按 75% 预警 / 90% 临界 两档阈值着色；超限与未设上限分别标注。
 * 下半区按租户查看计费计量流水与合计金额。
 */

/** 预警阈值：超过即转琥珀色，提示运营提前扩容或限流。 */
const WARN_PCT = 75
/** 临界阈值：超过即转红，接近或已触及上限。 */
const CRIT_PCT = 90

const METRIC_LABEL: Record<string, string> = {
  players: '玩家数',
  detectionVolume: '检测量',
  storageMb: '存储 (MB)',
}

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

/** 按使用率返回语义色；未设上限或无数据返回中性色。 */
function quotaColor(pct: number | undefined): string {
  if (pct === undefined) return 'var(--dim)'
  if (pct >= CRIT_PCT) return 'var(--kpi-red)'
  if (pct >= WARN_PCT) return 'var(--kpi-amber)'
  return 'var(--kpi-green)'
}

/** 单个配额指标的「用量 / 上限 + 进度条」。max 缺失或 ≤0 视为未设上限。 */
function QuotaCell({ metric }: { metric?: TenantQuotaMetric }) {
  const used = metric?.used
  const max = metric?.max
  const unlimited = max === undefined || max <= 0
  const pct = !unlimited && used !== undefined ? Math.min(100, (used / (max as number)) * 100) : undefined
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 150 }}>
      <span className="mono" style={{ fontSize: 12.5 }}>
        {used ?? '—'} <span style={{ color: 'var(--muted)' }}>/ {unlimited ? '∞' : max}</span>
      </span>
      {unlimited ? (
        <span style={{ fontSize: 11.5, color: 'var(--muted)' }}>未设上限</span>
      ) : (
        <Tooltip title={`使用率 ${pct?.toFixed(1)}%`}>
          <Progress
            percent={Math.round((pct ?? 0) * 10) / 10}
            showInfo={false}
            strokeColor={quotaColor(pct)}
            trailColor="rgba(255,255,255,.06)"
            size={['100%', 5]}
          />
        </Tooltip>
      )}
    </div>
  )
}

export default function TenantQuota() {
  const [quotas, setQuotas] = useState<TenantQuotaRow[]>([])
  const [quotaTotal, setQuotaTotal] = useState(0)
  const [usage, setUsage] = useState<TenantUsageRow[]>([])
  const [usageTotalAmount, setUsageTotalAmount] = useState(0)
  const [usageRowCount, setUsageRowCount] = useState(0)
  const [tenantId, setTenantId] = useState<string | undefined>(undefined)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [usageLoading, setUsageLoading] = useState(false)

  const { ref: quotaRef, reveal: revealQuota } = useTableRowReveal<HTMLDivElement>({ x: -12 })
  const { ref: usageRef, reveal: revealUsage } = useTableRowReveal<HTMLDivElement>({ x: 12 })

  const loadQuota = useCallback(async () => {
    try {
      const d = await api.tenant.quota()
      setQuotas(d.rows ?? [])
      setQuotaTotal(d.total ?? 0)
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  const loadUsage = useCallback(async () => {
    setUsageLoading(true)
    try {
      const d = await api.tenant.usage(tenantId)
      setUsage(d.rows ?? [])
      setUsageTotalAmount(d.totalAmount ?? 0)
      setUsageRowCount(d.rowCount ?? 0)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setUsageLoading(false)
    }
  }, [tenantId])

  useEffect(() => { void loadQuota() }, [loadQuota])
  useEffect(() => { void loadUsage() }, [loadUsage])

  useEffect(() => {
    const id = requestAnimationFrame(revealQuota)
    return () => cancelAnimationFrame(id)
  }, [quotas, revealQuota])

  useEffect(() => {
    const id = requestAnimationFrame(revealUsage)
    return () => cancelAnimationFrame(id)
  }, [usage, revealUsage])

  const refreshAll = useCallback(() => {
    setLoading(true)
    Promise.allSettled([loadQuota(), loadUsage()]).finally(() => setLoading(false))
  }, [loadQuota, loadUsage])

  const tenantOptions = useMemo(() => {
    const ids = new Set<string>()
    quotas.forEach((q) => { if (q.tenantId) ids.add(q.tenantId) })
    usage.forEach((u) => { if (u.tenantId) ids.add(u.tenantId) })
    if (tenantId) ids.add(tenantId)
    return Array.from(ids).map((id) => ({ label: id, value: id }))
  }, [quotas, usage, tenantId])

  // 汇总：有多少租户处于预警 / 临界档，给运营一个总览数字
  const alerts = useMemo(() => {
    let warn = 0
    let crit = 0
    for (const q of quotas) {
      for (const m of [q.players, q.detectionVolume, q.storageMb]) {
        if (!m || m.max === undefined || m.max <= 0 || m.used === undefined) continue
        const pct = (m.used / m.max) * 100
        if (pct >= CRIT_PCT) crit += 1
        else if (pct >= WARN_PCT) warn += 1
      }
    }
    return { warn, crit }
  }, [quotas])

  const quotaColumns: TableColumnsType<TenantQuotaRow> = [
    {
      title: '租户', dataIndex: 'tenantId', width: 180,
      render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span>,
    },
    { title: METRIC_LABEL.players, key: 'players', width: 190, render: (_, r) => <QuotaCell metric={r.players} /> },
    { title: METRIC_LABEL.detectionVolume, key: 'detection', width: 190, render: (_, r) => <QuotaCell metric={r.detectionVolume} /> },
    { title: METRIC_LABEL.storageMb, key: 'storage', width: 190, render: (_, r) => <QuotaCell metric={r.storageMb} /> },
    {
      title: '状态', key: 'state', width: 110,
      render: (_, r) => {
        let worst: number | undefined
        for (const m of [r.players, r.detectionVolume, r.storageMb]) {
          if (!m || m.max === undefined || m.max <= 0 || m.used === undefined) continue
          const pct = (m.used / m.max) * 100
          worst = worst === undefined ? pct : Math.max(worst, pct)
        }
        if (worst === undefined) return <Tag>未设上限</Tag>
        if (worst >= CRIT_PCT) return <Tag color="red">临界 {worst.toFixed(0)}%</Tag>
        if (worst >= WARN_PCT) return <Tag color="orange">预警 {worst.toFixed(0)}%</Tag>
        return <Tag color="green">正常 {worst.toFixed(0)}%</Tag>
      },
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 168, render: fmt },
  ]

  const usageColumns: TableColumnsType<TenantUsageRow> = [
    { title: 'ID', dataIndex: 'id', width: 74, render: (v?: number) => v ?? '-' },
    { title: '租户', dataIndex: 'tenantId', width: 170, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span> },
    { title: '计量项', dataIndex: 'metric', width: 150, render: (v?: string) => v || '-' },
    { title: '数量', dataIndex: 'quantity', width: 110, render: (v?: number) => (v == null ? '-' : v) },
    { title: '单价', dataIndex: 'unitPrice', width: 110, render: (v?: number) => (v == null ? '-' : v) },
    {
      title: '金额', dataIndex: 'amount', width: 120,
      render: (v?: number) => <span className="mono">{v == null ? '-' : v}</span>,
    },
    { title: '发生时间', dataIndex: 'occurredAt', width: 168, render: fmt },
  ]

  return (
    <div>
      <PageHeader
        title="租户配额"
        description={`使用率 ≥ ${WARN_PCT}% 预警、≥ ${CRIT_PCT}% 临界；未设上限按 ∞ 展示`}
        error={err}
        onCloseError={() => setErr('')}
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>}
      />

      <Card
        title="配额使用率"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={
          <Space size={14}>
            <span style={{ fontSize: 12, color: 'var(--kpi-amber)' }}>预警项 {alerts.warn}</span>
            <span style={{ fontSize: 12, color: 'var(--kpi-red)' }}>临界项 {alerts.crit}</span>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>共 {quotaTotal} 个租户</span>
          </Space>
        }
      >
        <div ref={quotaRef}>
          <Table<TenantQuotaRow>
            rowKey={(r, i) => r.tenantId ?? `tenant-${i}`}
            columns={quotaColumns}
            dataSource={quotas}
            scroll={{ x: 1100 }}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            locale={{ emptyText: '未取到租户配额' }}
          />
        </div>
      </Card>

      <Card
        title="计费计量流水"
        className="pacc-glass-md"
        extra={
          <Space size={12}>
            <Select
              allowClear
              showSearch
              placeholder="按租户筛选"
              style={{ width: 220 }}
              value={tenantId}
              onChange={(v) => setTenantId(v)}
              options={tenantOptions}
              filterOption={(input, option) => (option?.value ?? '').toLowerCase().includes(input.toLowerCase())}
            />
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>
              {usageRowCount} 条 · 合计 {usageTotalAmount}
            </span>
          </Space>
        }
      >
        <div ref={usageRef}>
          <Table<TenantUsageRow>
            rowKey={(r, i) => (r.id != null ? String(r.id) : `usage-${i}`)}
            columns={usageColumns}
            dataSource={usage}
            loading={usageLoading}
            scroll={{ x: 900 }}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            locale={{ emptyText: '暂无计量流水' }}
          />
        </div>
      </Card>
    </div>
  )
}