import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Form, Input, Modal, Popconfirm, Progress, Segmented, Space, Table, Tag, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import { api } from '../../api/client'
import type {
  AlertCorrelation, AlertNoiseGroup, AlertNoiseStats, AlertSuppressionRule,
} from '../../types'
import { useTableRowReveal } from '../../hooks/useGSAP'

/**
 * DF §4.3.2 智能告警降噪管理页。
 * 顶部把实测「降噪率」置于最显眼处，并给出验收 A23 目标线（≥60%）作为对照。
 */

/** 验收 A23：告警降噪率目标。 */
const A23_TARGET = 0.6

const WINDOWS = [
  { label: '24 小时', value: 24 },
  { label: '3 天', value: 72 },
  { label: '7 天', value: 168 },
]

const PRIORITY_COLOR: Record<string, string> = { P0: 'red', P1: 'orange', P2: 'default' }

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

export default function AlertNoise() {
  const [windowHours, setWindowHours] = useState(24)
  const [stats, setStats] = useState<AlertNoiseStats | null>(null)
  const [groups, setGroups] = useState<AlertNoiseGroup[]>([])
  const [groupTotal, setGroupTotal] = useState(0)
  const [correlated, setCorrelated] = useState<AlertCorrelation[]>([])
  const [rules, setRules] = useState<AlertSuppressionRule[]>([])
  const [page, setPage] = useState(0)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [form] = Form.useForm<{ name: string; pattern: string; familyCode?: string; reason?: string }>()

  const { ref: groupRef, reveal: revealGroups } = useTableRowReveal<HTMLDivElement>({ x: -12 })
  const { ref: ruleRef, reveal: revealRules } = useTableRowReveal<HTMLDivElement>({ x: 12 })

  // 独立加载器：任一路径失败只影响对应区块，其余照常展示
  const loadStats = useCallback(async () => {
    try {
      setStats(await api.alerts.noiseStats(windowHours))
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [windowHours])

  const loadGroups = useCallback(async () => {
    try {
      const d = await api.alerts.noiseGroups(page, 20, windowHours)
      setGroups(d.rows ?? [])
      setGroupTotal(d.total ?? 0)
      setCorrelated(d.correlated ?? [])
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [page, windowHours])

  const loadRules = useCallback(async () => {
    try {
      setRules(await api.alerts.suppressions())
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => { void loadStats() }, [loadStats])
  useEffect(() => { void loadGroups() }, [loadGroups])
  useEffect(() => { void loadRules() }, [loadRules])

  useEffect(() => {
    const id = requestAnimationFrame(revealGroups)
    return () => cancelAnimationFrame(id)
  }, [groups, revealGroups])

  useEffect(() => {
    const id = requestAnimationFrame(revealRules)
    return () => cancelAnimationFrame(id)
  }, [rules, revealRules])

  const refreshAll = useCallback(() => {
    setLoading(true)
    Promise.allSettled([loadStats(), loadGroups(), loadRules()]).finally(() => setLoading(false))
  }, [loadStats, loadGroups, loadRules])

  // 降噪率按后端口径渲染；后端未给字段时按 raw/aggregated 兜底推算，仍缺失则显示 —
  const rate = useMemo(() => {
    if (typeof stats?.reductionRate === 'number') return stats.reductionRate
    const raw = stats?.rawCount ?? 0
    const agg = stats?.aggregatedCount ?? 0
    if (raw <= 0) return undefined
    return (raw - agg) / raw
  }, [stats])

  const ratePercent = rate === undefined ? undefined : rate * 100
  const meets = rate !== undefined && rate >= A23_TARGET

  async function submitRule() {
    const v = await form.validateFields()
    setSaving(true)
    try {
      await api.alerts.addSuppression({
        name: v.name.trim(),
        pattern: v.pattern.trim(),
        familyCode: v.familyCode?.trim() || undefined,
        reason: v.reason?.trim() || undefined,
      })
      message.success('抑制规则已创建')
      setFormOpen(false)
      form.resetFields()
      await loadRules()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  async function removeRule(id?: string) {
    if (!id) return
    try {
      await api.alerts.removeSuppression(id)
      message.success('已删除抑制规则')
      await loadRules()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const groupColumns: TableColumnsType<AlertNoiseGroup> = [
    { title: '组 ID', dataIndex: 'id', width: 190, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v ?? '-'}</span> },
    { title: '优先级', dataIndex: 'priority', width: 84, render: (v?: string) => <Tag color={PRIORITY_COLOR[v ?? ''] ?? 'default'}>{v ?? '-'}</Tag> },
    { title: '严重度', dataIndex: 'severity', width: 78 },
    {
      title: '信号数', dataIndex: 'signalCount', width: 88,
      render: (v?: number) => <span style={{ color: (v ?? 0) > 20 ? '#ff4d3d' : undefined }}>{v ?? '-'}</span>,
    },
    { title: '家族', dataIndex: 'familyCode', width: 140, render: (v?: string) => v || '—' },
    { title: '规则', dataIndex: 'ruleId', width: 180, ellipsis: true, render: (v?: string) => v || '—' },
    { title: '玩家', dataIndex: 'playerId', width: 120, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '—'}</span> },
    { title: '首次出现', dataIndex: 'firstSeenAt', width: 168, render: fmt },
    { title: '最近出现', dataIndex: 'lastSeenAt', width: 168, render: fmt },
  ]

  const ruleColumns: TableColumnsType<AlertSuppressionRule> = [
    { title: '名称', dataIndex: 'name', width: 180, render: (v?: string) => v || '-' },
    {
      title: '匹配表达式', dataIndex: 'pattern', ellipsis: true,
      render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span>,
    },
    { title: '家族', dataIndex: 'familyCode', width: 130, render: (v?: string) => v || '—' },
    { title: '原因', dataIndex: 'reason', width: 200, ellipsis: true, render: (v?: string) => v || '—' },
    { title: '命中次数', dataIndex: 'hitCount', width: 96, render: (v?: number) => v ?? 0 },
    { title: '最近命中', dataIndex: 'lastHitAt', width: 168, render: fmt },
    { title: '创建人', dataIndex: 'createdBy', width: 110, render: (v?: string) => v || '-' },
    {
      title: '状态', dataIndex: 'enabled', width: 84,
      render: (v?: boolean) => (v === false ? <Tag>已停用</Tag> : <Tag color="green">生效中</Tag>),
    },
    {
      title: '操作', width: 86, fixed: 'right',
      render: (_, r) => (
        <Popconfirm
          title="删除该抑制规则？"
          description="删除后命中的误报告警将不再被静默。"
          okText="删除"
          okButtonProps={{ danger: true }}
          cancelText="取消"
          onConfirm={() => void removeRule(r.id)}
        >
          <Button size="small" type="text" danger disabled={!r.id}>删除</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="告警降噪"
        description="聚合窗口内的原始信号 → 聚合组，抑制误报；降噪率为 A23 验收口径"
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <Space wrap>
            <Segmented
              value={windowHours}
              onChange={(v) => { setPage(0); setWindowHours(v as number) }}
              options={WINDOWS}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>
          </Space>
        }
      />

      {/* 降噪率置于首屏最显眼处：大号等宽数字 + 与 60% 目标线的对照 */}
      <section
        className="pacc-glass-lg"
        style={{
          border: '1px solid var(--border)', background: 'var(--panel)',
          padding: '17px 19px 15px', marginBottom: 15,
          display: 'flex', gap: 22, flexWrap: 'wrap', alignItems: 'flex-end',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={{ fontSize: 11.5, letterSpacing: '.14em', textTransform: 'uppercase', color: 'var(--muted)' }}>
            实测降噪率
          </span>
          <span className="mono" style={{
            fontSize: 46, fontWeight: 700, lineHeight: 1.02, letterSpacing: '-.01em',
            color: ratePercent === undefined ? 'var(--dim)' : meets ? 'var(--kpi-green)' : 'var(--kpi-amber)',
          }}>
            {ratePercent === undefined ? '—' : `${ratePercent.toFixed(2)}%`}
          </span>
        </div>

        <div style={{ flex: '1 1 280px', minWidth: 240, display: 'flex', flexDirection: 'column', gap: 7 }}>
          <Progress
            percent={ratePercent === undefined ? 0 : Math.min(100, Math.round(ratePercent * 10) / 10)}
            showInfo={false}
            strokeColor={meets ? 'var(--kpi-green)' : 'var(--kpi-amber)'}
            trailColor="rgba(255,255,255,.06)"
            size={['100%', 7]}
          />
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>
            目标线 60%（A23）·
            {ratePercent === undefined
              ? ' 窗口内暂无可观测信号'
              : meets ? ' 已达标' : ` 距目标还差 ${(60 - ratePercent).toFixed(2)} 个百分点`}
          </span>
        </div>

        <div style={{ flex: '0 0 auto', display: 'flex', gap: 18 }}>
          {[
            { k: '原始信号', v: stats?.rawCount, c: 'var(--kpi-red)' },
            { k: '聚合后', v: stats?.aggregatedCount, c: 'var(--kpi-blue)' },
            { k: '被抑制', v: stats?.suppressedCount, c: 'var(--kpi-amber)' },
          ].map((m) => (
            <div key={m.k} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontSize: 11.5, color: 'var(--muted)' }}>{m.k}</span>
              <span className="mono" style={{ fontSize: 22, fontWeight: 700, color: m.c }}>
                {m.v ?? '—'}
              </span>
            </div>
          ))}
        </div>
      </section>

      <Card
        title="聚合告警组"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>
            共 {groupTotal} 组 · 跨规则家族相关性 {correlated.length} 组
          </span>
        }
      >
        <div ref={groupRef}>
          <Table<AlertNoiseGroup>
            rowKey={(r, i) => r.id ?? `g-${i}`}
            columns={groupColumns}
            dataSource={groups}
            scroll={{ x: 1240 }}
            pagination={{
              current: page + 1,
              pageSize: 20,
              total: groupTotal,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            locale={{ emptyText: '该窗口内没有聚合告警组' }}
          />
        </div>
        {correlated.length > 0 && (
          <div style={{ marginTop: 12, borderTop: '1px solid var(--border)', paddingTop: 11 }}>
            <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 7 }}>
              家族相关性（同一家族在窗口内出现多个独立聚合组，往往指向同一作弊家族的多点投放）
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              {correlated.map((c, i) => (
                <div
                  key={`${c.familyCode ?? 'f'}-${i}`}
                  style={{
                    display: 'flex', gap: 14, alignItems: 'baseline',
                    padding: '8px 2px', borderBottom: '1px solid var(--border)',
                  }}
                >
                  <span style={{ flex: '0 0 180px', fontSize: 12.5 }}>{c.familyCode ?? '未命名家族'}</span>
                  <span className="mono" style={{ flex: '0 0 110px', fontSize: 12.5, color: 'var(--muted)' }}>
                    {c.groupCount ?? 0} 组 / {c.signalCount ?? 0} 信号
                  </span>
                  <span style={{ fontSize: 12, color: 'var(--kpi-red)' }}>最高严重度 {c.maxSeverity ?? '-'}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </Card>

      <Card
        title="误报抑制规则"
        className="pacc-glass-md"
        extra={
          <Space>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>命中即静默，删除前请确认不会掩盖真实告警</span>
            <Button size="small" icon={<PlusOutlined />} onClick={() => setFormOpen(true)}>新建规则</Button>
          </Space>
        }
      >
        <div ref={ruleRef}>
          <Table<AlertSuppressionRule>
            rowKey={(r, i) => r.id ?? `r-${i}`}
            columns={ruleColumns}
            dataSource={rules}
            scroll={{ x: 1080 }}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            locale={{ emptyText: '暂无抑制规则' }}
          />
        </div>
      </Card>

      <Modal
        title="新建误报抑制规则"
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void submitRule()}
        confirmLoading={saving}
        okText="创建"
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={form} layout="vertical" requiredMark="optional">
          <Form.Item
            name="name"
            label="规则名称"
            rules={[{ required: true, message: '请填写规则名称' }]}
          >
            <Input placeholder="例如：社区服高清材质误报" maxLength={120} />
          </Form.Item>
          <Form.Item
            name="pattern"
            label="匹配表达式"
            extra="用于匹配告警标题或规则 ID 的关键字 / 正则片段"
            rules={[{ required: true, message: '请填写匹配表达式' }]}
          >
            <Input placeholder="例如：texture_pack_highres" maxLength={200} />
          </Form.Item>
          <Form.Item name="familyCode" label="限定家族（可选）">
            <Input placeholder="仅抑制该作弊家族" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="reason"
            label="抑制原因"
            rules={[{ required: true, message: '请填写抑制原因（便于事后审计）' }]}
          >
            <Input.TextArea rows={3} placeholder="说明为何判定为误报" maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}