import { useCallback, useEffect, useState } from 'react'
import {
  Button, Card, Modal, Space, Switch, Table, Tag, Tooltip, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, UndoOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import { api } from '../../api/client'
import type { AutomationExecutionRow, AutomationRuleRow } from '../../types'
import { useTableRowReveal } from '../../hooks/useGSAP'

/**
 * DF §4.3.3 自动化响应管理页。
 * 上半区：内置触发→动作规则（5 条），启停需二次确认 —— 关停可能让真实威胁失去自动处置。
 * 下半区：执行审计日志，可对可逆动作发起回滚。
 */

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', FAILED: 'red', SKIPPED: 'default', PENDING: 'gold',
  REVERTED: 'blue', PARTIAL: 'orange',
}

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

export default function Automation() {
  const [rules, setRules] = useState<AutomationRuleRow[]>([])
  const [executions, setExecutions] = useState<AutomationExecutionRow[]>([])
  const [execTotal, setExecTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [busyCode, setBusyCode] = useState('')
  const [revertingId, setRevertingId] = useState<number | null>(null)

  const { ref: ruleRef, reveal: revealRules } = useTableRowReveal<HTMLDivElement>({ x: -12 })
  const { ref: execRef, reveal: revealExec } = useTableRowReveal<HTMLDivElement>({ x: 12 })

  const loadRules = useCallback(async () => {
    try {
      setRules(await api.automation.rules())
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  const loadExecutions = useCallback(async () => {
    try {
      const d = await api.automation.executions(page, 20)
      setExecutions(d.rows ?? [])
      setExecTotal(d.total ?? 0)
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [page])

  useEffect(() => { void loadRules() }, [loadRules])
  useEffect(() => { void loadExecutions() }, [loadExecutions])

  useEffect(() => {
    const id = requestAnimationFrame(revealRules)
    return () => cancelAnimationFrame(id)
  }, [rules, revealRules])

  useEffect(() => {
    const id = requestAnimationFrame(revealExec)
    return () => cancelAnimationFrame(id)
  }, [executions, revealExec])

  const refreshAll = useCallback(() => {
    setLoading(true)
    Promise.allSettled([loadRules(), loadExecutions()]).finally(() => setLoading(false))
  }, [loadRules, loadExecutions])

  // 启停二次确认：关停自动化等于放弃自动处置，必须让操作者自查一次后果
  function askToggle(rule: AutomationRuleRow, next: boolean) {
    const code = rule.code
    if (!code) return
    const name = rule.name || code
    Modal.confirm({
      title: next ? `启用自动化规则「${name}」？` : `停用自动化规则「${name}」？`,
      content: next
        ? `启用后，${rule.trigger || '触发条件'}命中时将自动执行 ${rule.action || '既定动作'}，无需人工介入。`
        : `停用后，${rule.trigger || '该触发条件'}命中时将不再自动执行 ${rule.action || '既定动作'}，需人工跟进。`,
      okText: next ? '启用' : '停用',
      okButtonProps: { danger: !next },
      cancelText: '取消',
      onOk: async () => {
        setBusyCode(code)
        try {
          await api.automation.toggleRule(code, next)
          message.success(next ? '已启用该规则' : '已停用该规则')
          await loadRules()
          await loadExecutions()
        } catch (e) {
          message.error((e as Error).message)
        } finally {
          setBusyCode('')
        }
      },
    })
  }

  function askRevert(row: AutomationExecutionRow) {
    if (row.id == null) return
    Modal.confirm({
      title: '回滚这次自动化执行？',
      content: `将撤销规则 ${row.ruleCode || '-'} 于 ${fmt(row.executedAt)} 执行的 ${row.actionCode || '动作'}。该操作会写入审计日志。`,
      okText: '回滚',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setRevertingId(row.id ?? null)
        try {
          await api.automation.revertExecution(row.id as number)
          message.success('已提交回滚')
          await loadExecutions()
        } catch (e) {
          message.error((e as Error).message)
        } finally {
          setRevertingId(null)
        }
      },
    })
  }

  const ruleColumns: TableColumnsType<AutomationRuleRow> = [
    {
      title: '规则', dataIndex: 'name', width: 200,
      render: (v: string | undefined, r) => (
        <Space size={6} direction="vertical" style={{ gap: 1 }}>
          <span>{v || r.code || '-'}</span>
          <span className="mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>{r.code || '-'}</span>
        </Space>
      ),
    },
    {
      title: '触发条件', dataIndex: 'trigger', width: 240, ellipsis: true,
      render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '—'}</span>,
    },
    {
      title: '执行动作', dataIndex: 'action', width: 240, ellipsis: true,
      render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '—'}</span>,
    },
    {
      title: '阈值', dataIndex: 'threshold', width: 84,
      render: (v?: number) => (v == null ? '—' : v),
    },
    {
      title: '窗口', dataIndex: 'windowMin', width: 84,
      render: (v?: number) => (v == null ? '—' : `${v} 分`),
    },
    {
      title: '冷却', dataIndex: 'cooldownMin', width: 84,
      render: (v?: number) => (v == null ? '—' : `${v} 分`),
    },
    {
      title: '来源', dataIndex: 'builtin', width: 78,
      render: (v?: boolean) => (v === false ? <Tag>自定义</Tag> : <Tag color="geekblue">内置</Tag>),
    },
    { title: '最近触发', dataIndex: 'lastFiredAt', width: 168, render: fmt },
    {
      title: '状态', dataIndex: 'enabled', width: 100, fixed: 'right',
      render: (v: boolean | undefined, r) => (
        <Tooltip title={v ? '点击停用需二次确认' : '点击启用需二次确认'}>
          <Switch
            size="small"
            checked={v !== false}
            loading={busyCode === r.code}
            disabled={!r.code}
            onChange={(next) => askToggle(r, next)}
          />
        </Tooltip>
      ),
    },
  ]

  const execColumns: TableColumnsType<AutomationExecutionRow> = [
    { title: 'ID', dataIndex: 'id', width: 74, render: (v?: number) => v ?? '-' },
    { title: '规则', dataIndex: 'ruleCode', width: 180, ellipsis: true, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span> },
    { title: '动作', dataIndex: 'actionCode', width: 170, ellipsis: true, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span> },
    {
      title: '结果', dataIndex: 'status', width: 96,
      render: (v?: string) => <Tag color={STATUS_COLOR[v ?? ''] ?? 'default'}>{v || '-'}</Tag>,
    },
    { title: '详情', dataIndex: 'detail', ellipsis: true, render: (v?: string) => v || '—' },
    { title: '执行人', dataIndex: 'executedBy', width: 110, render: (v?: string) => v || '-' },
    { title: '执行时间', dataIndex: 'executedAt', width: 168, render: fmt },
    {
      title: '回滚', width: 96, fixed: 'right',
      render: (_, r) => {
        if (r.reverted) return <Tag color="blue">已回滚</Tag>
        if (!r.reversible) return <span style={{ color: 'var(--muted)' }}>不可逆</span>
        return (
          <Button
            size="small"
            type="text"
            icon={<UndoOutlined />}
            loading={revertingId === r.id}
            disabled={r.id == null}
            onClick={() => askRevert(r)}
          >
            回滚
          </Button>
        )
      },
    },
  ]

  const enabledCount = rules.filter((r) => r.enabled !== false).length

  return (
    <div>
      <PageHeader
        title="自动化响应"
        description="内置 5 条 触发→动作 规则；启停与回滚均写审计"
        error={err}
        onCloseError={() => setErr('')}
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>}
      />

      <Card
        title="触发 → 动作 规则"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>
            共 {rules.length} 条 · 生效中 {enabledCount} 条
          </span>
        }
      >
        <div ref={ruleRef}>
          <Table<AutomationRuleRow>
            rowKey={(r, i) => r.code ?? `rule-${i}`}
            columns={ruleColumns}
            dataSource={rules}
            scroll={{ x: 1280 }}
            pagination={false}
            locale={{ emptyText: '未取到自动化规则' }}
          />
        </div>
      </Card>

      <Card
        title="执行审计日志"
        className="pacc-glass-md"
        extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>每条自动处置都留痕，可逆动作支持回滚</span>}
      >
        <div ref={execRef}>
          <Table<AutomationExecutionRow>
            rowKey={(r, i) => (r.id != null ? String(r.id) : `exec-${i}`)}
            columns={execColumns}
            dataSource={executions}
            scroll={{ x: 1180 }}
            pagination={{
              current: page + 1,
              pageSize: 20,
              total: execTotal,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            locale={{ emptyText: '暂无自动化执行记录' }}
          />
        </div>
      </Card>
    </div>
  )
}