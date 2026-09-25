import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Descriptions, Drawer, Modal, Segmented, Select, Slider, Space,
  Table, Tag, Tooltip, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ExperimentOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import { api } from '../../api/client'
import type { V52ModelVersion, V52TrainResult } from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

const STATUS_META: Record<string, { label: string; color: string }> = {
  draft: { label: '草稿', color: 'default' },
  gray: { label: '灰度中', color: 'orange' },
  active: { label: '已生效', color: 'green' },
  rollback: { label: '已回退', color: 'blue' },
}

const MODEL_TYPES = ['XGBOOST', 'AUTOENCODER']
const GRAY_PRESETS = [1, 10, 50, 100]

function pct(v: number | undefined) {
  return `${((v ?? 0) * 100).toFixed(2)}%`
}

function fmt(ts: string | undefined) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

export default function ModelManager() {
  const [rows, setRows] = useState<V52ModelVersion[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const [statusFilter, setStatusFilter] = useState('all')
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined)
  const [detail, setDetail] = useState<V52ModelVersion | null>(null)
  const [grayTarget, setGrayTarget] = useState<V52ModelVersion | null>(null)
  const [grayPercent, setGrayPercent] = useState(10)
  const [busy, setBusy] = useState('')
  const [trainOpen, setTrainOpen] = useState(false)
  const [trainResult, setTrainResult] = useState<V52TrainResult | null>(null)
  const [trainLoading, setTrainLoading] = useState(false)

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })
  const revealModal = useModalReveal()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.v52.model.versions(typeFilter)
      setRows(d.versions ?? [])
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [typeFilter])

  useEffect(() => {
    void load()
  }, [load])

  const visible = useMemo(
    () => rows.filter((r) => statusFilter === 'all' || r.status === statusFilter),
    [rows, statusFilter],
  )

  const stats = useMemo(() => {
    const active = rows.filter((r) => r.status === 'active').length
    const gray = rows.filter((r) => r.status === 'gray').length
    const best = rows.reduce((m, r) => Math.max(m, r.accuracy ?? 0), 0)
    const fpr = rows.length ? rows.reduce((s, r) => s + (r.false_positive_rate ?? 0), 0) / rows.length : 0
    return { active, gray, best, fpr }
  }, [rows])

  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [visible, reveal])

  useEffect(() => {
    if (!grayTarget && !trainOpen) return
    const id = requestAnimationFrame(revealModal)
    return () => cancelAnimationFrame(id)
  }, [grayTarget, trainOpen, revealModal])

  async function submitGray() {
    if (!grayTarget) return
    setBusy('gray')
    try {
      await api.v52.model.gray(grayTarget.id, grayPercent)
      message.success(`${grayTarget.model_type} ${grayTarget.version} 已放量 ${grayPercent}%`)
      setGrayTarget(null)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy('')
    }
  }

  async function activate(row: V52ModelVersion) {
    setBusy('activate')
    try {
      await api.v52.model.activate(row.id)
      message.success(`${row.model_type} ${row.version} 已全量生效`)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy('')
    }
  }

  async function rollback(row: V52ModelVersion) {
    setBusy('rollback')
    try {
      await api.v52.model.rollback(row.id)
      message.success(`已回退至 ${row.model_type} ${row.version}`)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy('')
    }
  }

  async function runTrain() {
    setTrainLoading(true)
    try {
      const r = await api.v52.model.train()
      setTrainResult(r)
      if (r.trained) await load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setTrainLoading(false)
    }
  }

  // 列表接口不返回签名；打开详情时对生效版本补一次签名查询
  function openDetail(row: V52ModelVersion) {
    setDetail(row)
    if (row.status !== 'active') return
    api.v52.model.active(row.model_type)
      .then((a) => setDetail((cur) => (cur && cur.id === a.id ? a : cur)))
      .catch(() => { /* 无生效版本时保持列表数据 */ })
  }

  const columns: TableColumnsType<V52ModelVersion> = [
    {
      title: '类型', dataIndex: 'model_type', width: 130,
      render: (v: string) => <Tag color={v === 'XGBOOST' ? 'geekblue' : 'purple'}>{v}</Tag>,
    },
    { title: '版本', dataIndex: 'version', width: 110 },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (v: string, r) => (
        <Space size={6}>
          <Tag color={STATUS_META[v]?.color ?? 'default'}>{STATUS_META[v]?.label ?? v}</Tag>
          {v === 'gray' && <span style={{ fontSize: 12, color: 'var(--muted)' }}>{r.gray_percent}%</span>}
        </Space>
      ),
    },
    { title: '准确率', dataIndex: 'accuracy', width: 100, render: (v: number) => pct(v) },
    {
      title: '误报率', dataIndex: 'false_positive_rate', width: 100,
      render: (v: number) => (
        <span style={{ color: v > 0.03 ? 'var(--danger, #ff4d3d)' : undefined }}>{pct(v)}</span>
      ),
    },
    { title: '召回率', dataIndex: 'recall', width: 100, render: (v: number) => pct(v) },
    { title: '训练样本', dataIndex: 'training_samples', width: 100 },
    { title: '登记时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '操作', width: 240, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          {r.status !== 'active' && (
            <Button size="small" onClick={() => { setGrayTarget(r); setGrayPercent(r.gray_percent || 10) }}>
              {r.status === 'gray' ? '调整灰度' : '灰度'}
            </Button>
          )}
          {r.status === 'gray' && (
            <Button size="small" type="primary" loading={busy === 'activate'} onClick={() => Modal.confirm({
              title: `确认将 ${r.model_type} ${r.version} 提升为全量生效？`,
              content: '当前生效版本会落为可回退状态。',
              onOk: () => activate(r),
            })}>全量上线</Button>
          )}
          {r.status === 'rollback' && (
            <Button size="small" type="primary" loading={busy === 'rollback'} onClick={() => Modal.confirm({
              title: `确认回退至 ${r.model_type} ${r.version}？`,
              content: '该版本将立即全量生效，当前生效版本落为可回退状态。',
              okButtonProps: { danger: true },
              onOk: () => rollback(r),
            })}>回退生效</Button>
          )}
          {r.status === 'draft' && (
            <Tooltip title="草稿版本未通过准确率/误报率门禁，只能先灰度验证">
              <Button size="small" type="primary" onClick={() => { setGrayTarget(r); setGrayPercent(10) }}>灰度验证</Button>
            </Tooltip>
          )}
          <Button size="small" type="text" onClick={() => openDetail(r)}>详情</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="模型版本管理"
        description="端侧三模型（XGBoost / Autoencoder）的灰度、激活、回退与手动训练"
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <Space>
            <Button icon={<ExperimentOutlined />} onClick={() => { setTrainOpen(true); setTrainResult(null) }}>
              手动训练
            </Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
          </Space>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="版本总数" value={rows.length} />
        <MetricCard label="灰度中" value={stats.gray} accent="#ffa940" />
        <MetricCard label="已生效" value={stats.active} accent="#52c41a" hint="每类型当前生效 1 个版本" />
        <MetricCard label="最高准确率" value={pct(stats.best)} accent="#4096ff" hint={`平均误报率 ${pct(stats.fpr)}`} />
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 13, flexWrap: 'wrap' }}>
        <Segmented
          value={statusFilter}
          onChange={(v) => setStatusFilter(v as string)}
          options={[
            { label: '全部', value: 'all' },
            { label: '草稿', value: 'draft' },
            { label: '灰度中', value: 'gray' },
            { label: '已生效', value: 'active' },
            { label: '已回退', value: 'rollback' },
          ]}
        />
        <Select
          allowClear
          placeholder="全部模型类型"
          value={typeFilter}
          onChange={(v) => setTypeFilter(v)}
          style={{ width: 170 }}
          options={MODEL_TYPES.map((t) => ({ label: t, value: t }))}
        />
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>共 {visible.length} 条</span>
      </div>

      <Card styles={{ body: { padding: 0 } }} className="pacc-glass-md">
        <div ref={tableRef}>
          <Table<V52ModelVersion>
            rowKey="id"
            columns={columns}
            dataSource={visible}
            loading={loading}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            scroll={{ x: 1180 }}
            locale={{ emptyText: '暂无模型版本，可在客户端上报样本后触发手动训练' }}
          />
        </div>
      </Card>

      <Modal
        title={`灰度放量 · ${grayTarget?.model_type ?? ''} ${grayTarget?.version ?? ''}`}
        open={!!grayTarget}
        onCancel={() => setGrayTarget(null)}
        onOk={() => void submitGray()}
        okText="确认放量"
        confirmLoading={busy === 'gray'}
        destroyOnClose
      >
        <p style={{ color: 'var(--muted)', fontSize: 12.5, marginBottom: 14 }}>
          灰度命中按 PTEID 确定性分桶，同一玩家结果稳定；灰度期间原生效版本不撤下，可随时激活或回退。
        </p>
        <Slider min={0} max={100} value={grayPercent} onChange={setGrayPercent} tooltip={{ open: true }} />
        <Space size={6} style={{ marginTop: 4 }}>
          {GRAY_PRESETS.map((p) => (
            <Button key={p} size="small" onClick={() => setGrayPercent(p)}>{p}%</Button>
          ))}
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>当前 {grayPercent}%</span>
        </Space>
      </Modal>

      <Modal
        title="手动触发模型训练"
        open={trainOpen}
        onCancel={() => setTrainOpen(false)}
        onOk={() => void runTrain()}
        okText={trainResult ? '重新训练' : '开始训练'}
        confirmLoading={trainLoading}
        width={640}
        destroyOnClose
      >
        <p style={{ color: 'var(--muted)', fontSize: 12.5 }}>
          训练窗口与发布门禁由后端常量决定（近 7 天样本、准确率 ≥90%、误报率 ≤3%）；样本不足或未过门禁时不发布新版本。
        </p>
        {trainResult && (
          <>
            <Alert
              style={{ marginTop: 12 }}
              type={trainResult.trained ? 'success' : 'warning'}
              showIcon
              message={trainResult.trained ? '训练完成并已登记版本' : '本轮未产出新版本'}
              description={trainResult.trained
                ? `样本 ${trainResult.samples ?? '-'} · 特征维 ${trainResult.feature_dim ?? '-'} · 正/负 ${trainResult.positives ?? '-'}/${trainResult.negatives ?? '-'} · 切分 ${trainResult.split ?? '-'}（训练 ${trainResult.train_samples ?? '-'} / 评估 ${trainResult.eval_samples ?? '-'}）`
                : trainResult.reason}
            />
            {!!trainResult.models?.length && (
              <Card size="small" style={{ marginTop: 12 }} title="各模型评估指标">
                {trainResult.models.map((m, i) => (
                  <div key={i} style={{ fontSize: 12.5, lineHeight: 1.9, fontFamily: 'monospace' }}>
                    {Object.entries(m).map(([k, v]) => `${k}=${typeof v === 'number' ? Number(v).toFixed(4) : String(v)}`).join('  ')}
                  </div>
                ))}
              </Card>
            )}
          </>
        )}
      </Modal>

      <Drawer
        title="模型版本详情"
        open={!!detail}
        onClose={() => setDetail(null)}
        width={520}
        extra={<Button onClick={() => void load()} icon={<ReloadOutlined />}>刷新列表</Button>}
      >
        {detail && (
          <Descriptions column={1} size="small" bordered
            items={[
              { key: 'id', label: '版本 ID', children: detail.id },
              { key: 'type', label: '模型类型', children: detail.model_type },
              { key: 'version', label: '版本号', children: detail.version },
              { key: 'status', label: '状态', children: `${STATUS_META[detail.status]?.label ?? detail.status}（灰度 ${detail.gray_percent}%）` },
              { key: 'acc', label: '准确率', children: pct(detail.accuracy) },
              { key: 'fpr', label: '误报率', children: pct(detail.false_positive_rate) },
              { key: 'recall', label: '召回率', children: pct(detail.recall) },
              { key: 'samples', label: '训练样本', children: detail.training_samples },
              { key: 'created', label: '登记时间', children: fmt(detail.created_at) },
              { key: 'published', label: '发布时间', children: fmt(detail.published_at) },
              { key: 'file', label: '下发地址', children: detail.file_url || '-' },
              { key: 'sha256', label: 'SHA256', children: <span style={{ fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all' }}>{detail.sha256 || '-'}</span> },
              { key: 'sig', label: '签名', children: <span style={{ fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all' }}>{detail.signature || '（列表接口不返回签名，需实时查询生效版本）'}</span> },
            ]}
          />
        )}
      </Drawer>
    </div>
  )
}