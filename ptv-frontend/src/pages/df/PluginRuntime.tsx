import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Input, Modal, Space, Table, Tag, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, ThunderboltOutlined, PoweroffOutlined } from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import { api } from '../../api/client'
import type { PluginMarketRow, PluginRuntimeRow } from '../../types'
import { useTableRowReveal } from '../../hooks/useGSAP'

/**
 * DF §4.2.2 插件运行时管理页。
 * 市场条目（可安装）与运行时实例（已加载）分两块呈现。
 * 热加载 / 卸载会执行插件字节码，属高危动作：一律走带警示文案的二次确认弹层。
 */

const STATE_COLOR: Record<string, string> = {
  LOADED: 'green', ACTIVE: 'green', READY: 'blue', UNLOADED: 'default',
  STOPPED: 'default', ERROR: 'red', FAILED: 'red', LOADING: 'gold',
}

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

/** declaredApis 后端为逗号分隔串，拆成标签展示。 */
function splitApis(raw?: string): string[] {
  if (!raw) return []
  return raw.split(',').map((s) => s.trim()).filter(Boolean)
}

export default function PluginRuntime() {
  const [market, setMarket] = useState<PluginMarketRow[]>([])
  const [marketTotal, setMarketTotal] = useState(0)
  const [runtime, setRuntime] = useState<PluginRuntimeRow[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [busyId, setBusyId] = useState('')
  const [loadTarget, setLoadTarget] = useState<PluginRuntimeRow | null>(null)
  const [loadPath, setLoadPath] = useState('')
  const [loadBusy, setLoadBusy] = useState(false)

  const { ref: marketRef, reveal: revealMarket } = useTableRowReveal<HTMLDivElement>({ x: -12 })
  const { ref: runtimeRef, reveal: revealRuntime } = useTableRowReveal<HTMLDivElement>({ x: 12 })

  const loadMarket = useCallback(async () => {
    try {
      const d = await api.plugins.market(undefined, 0, 50)
      setMarket(d.rows ?? [])
      setMarketTotal(d.total ?? 0)
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  const loadRuntime = useCallback(async () => {
    try {
      setRuntime(await api.plugins.runtime())
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => { void loadMarket() }, [loadMarket])
  useEffect(() => { void loadRuntime() }, [loadRuntime])

  useEffect(() => {
    const id = requestAnimationFrame(revealMarket)
    return () => cancelAnimationFrame(id)
  }, [market, revealMarket])

  useEffect(() => {
    const id = requestAnimationFrame(revealRuntime)
    return () => cancelAnimationFrame(id)
  }, [runtime, revealRuntime])

  const refreshAll = useCallback(() => {
    setLoading(true)
    Promise.allSettled([loadMarket(), loadRuntime()]).finally(() => setLoading(false))
  }, [loadMarket, loadRuntime])

  // 加载：先弹确认（含可选路径覆盖），确认后才调用 load 接口
  function openLoad(row: PluginRuntimeRow) {
    setLoadTarget(row)
    setLoadPath(row.classPath ?? '')
  }

  async function confirmLoad() {
    const row = loadTarget
    if (!row?.id) return
    setLoadBusy(true)
    try {
      await api.plugins.loadRuntime(row.id, loadPath.trim() || undefined)
      message.success('插件已加载')
      setLoadTarget(null)
      await loadRuntime()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoadBusy(false)
    }
  }

  function askUnload(row: PluginRuntimeRow) {
    if (!row.id) return
    Modal.confirm({
      title: `卸载插件「${row.name || row.id}」？`,
      content:
        '卸载会停止该插件并释放其占用的运行时资源。若仍有流程依赖它声明的 API，相关调用会立即失败。'
        + '该操作会写入审计日志，请确认已评估影响。',
      okText: '卸载',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setBusyId(row.id as string)
        try {
          await api.plugins.unloadRuntime(row.id as string)
          message.success('插件已卸载')
          await loadRuntime()
        } catch (e) {
          message.error((e as Error).message)
        } finally {
          setBusyId('')
        }
      },
    })
  }

  const runtimeColumns: TableColumnsType<PluginRuntimeRow> = [
    {
      title: '插件', dataIndex: 'name', width: 200,
      render: (v: string | undefined, r) => (
        <Space size={6} direction="vertical" style={{ gap: 1 }}>
          <span>{v || r.id || '-'}</span>
          <span className="mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>{r.id || '-'}</span>
        </Space>
      ),
    },
    { title: '版本', dataIndex: 'version', width: 92, render: (v?: string) => v || '-' },
    {
      title: '状态', dataIndex: 'state', width: 96,
      render: (v?: string) => <Tag color={STATE_COLOR[v ?? ''] ?? 'default'}>{v || '-'}</Tag>,
    },
    {
      title: '累计 CPU', dataIndex: 'cpuMs', width: 108,
      render: (v?: number) => (v == null ? '—' : `${v.toFixed(1)} ms`),
    },
    {
      title: '错误数', dataIndex: 'errorCount', width: 84,
      render: (v?: number) => <span style={{ color: (v ?? 0) > 0 ? 'var(--kpi-red)' : undefined }}>{v ?? 0}</span>,
    },
    {
      title: '声明 API', dataIndex: 'declaredApis', width: 260,
      render: (v?: string) => {
        const apis = splitApis(v)
        if (apis.length === 0) return <span style={{ color: 'var(--muted)' }}>—</span>
        return (
          <Space size={4} wrap>
            {apis.slice(0, 4).map((a) => <Tag key={a} className="mono" style={{ fontSize: 11.5 }}>{a}</Tag>)}
            {apis.length > 4 && <MoreApisTag apis={apis} />}
          </Space>
        )
      },
    },
    { title: '类路径', dataIndex: 'classPath', ellipsis: true, render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '—'}</span> },
    { title: '加载时间', dataIndex: 'loadedAt', width: 168, render: fmt },
    {
      title: '操作', width: 150, fixed: 'right',
      render: (_, r) => {
        const loaded = ['LOADED', 'ACTIVE', 'READY'].includes((r.state ?? '').toUpperCase())
        return (
          <Space size={2}>
            <Button
              size="small"
              type="text"
              icon={<ThunderboltOutlined />}
              disabled={!r.id || busyId === r.id}
              onClick={() => openLoad(r)}
            >
              加载
            </Button>
            <Button
              size="small"
              type="text"
              danger
              icon={<PoweroffOutlined />}
              loading={busyId === r.id}
              disabled={!r.id || !loaded}
              onClick={() => askUnload(r)}
            >
              卸载
            </Button>
          </Space>
        )
      },
    },
  ]

  const marketColumns: TableColumnsType<PluginMarketRow> = [
    { title: '插件', dataIndex: 'name', width: 200, render: (v: string | undefined, r) => v || r.plugin_id || '-' },
    {
      title: '标识', dataIndex: 'plugin_id', width: 190,
      render: (v?: string) => <span className="mono" style={{ fontSize: 12 }}>{v || '-'}</span>,
    },
    { title: '类型', dataIndex: 'type', width: 108, render: (v?: string) => v || '-' },
    { title: '作者', dataIndex: 'author', width: 130, render: (v?: string) => v || '-' },
    { title: '版本', dataIndex: 'plugin_version', width: 92, render: (v?: string) => v || '-' },
    {
      title: '状态', dataIndex: 'status', width: 96,
      render: (v?: string) => <Tag>{v || '-'}</Tag>,
    },
    { title: '下载量', dataIndex: 'downloads', width: 92, render: (v?: number) => v ?? 0 },
    {
      title: '评分', dataIndex: 'avg_rating', width: 110,
      render: (v: number | undefined, r) => (v == null ? '—' : `${v.toFixed(1)}（${r.rating_count ?? 0}）`),
    },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v?: string) => v || '—' },
  ]

  const loadedCount = runtime.filter((r) => ['LOADED', 'ACTIVE', 'READY'].includes((r.state ?? '').toUpperCase())).length

  return (
    <div>
      <PageHeader
        title="插件运行时"
        description="热加载会执行插件字节码，属高危动作；加载与卸载均需显式确认"
        error={err}
        onCloseError={() => setErr('')}
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={refreshAll}>刷新</Button>}
      />

      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 15 }}
        message="插件代码以进程内方式运行"
        description="加载后插件可访问声明的宿主 API。请仅加载来源可信、已审核的插件；卸载前确认无流程依赖其声明的接口。"
      />

      <Card
        title="运行时实例"
        className="pacc-glass-md"
        style={{ marginBottom: 15 }}
        extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>共 {runtime.length} 个 · 运行中 {loadedCount} 个</span>}
      >
        <div ref={runtimeRef}>
          <Table<PluginRuntimeRow>
            rowKey={(r, i) => r.id ?? `rt-${i}`}
            columns={runtimeColumns}
            dataSource={runtime}
            scroll={{ x: 1460 }}
            pagination={false}
            locale={{ emptyText: '当前没有插件运行时实例' }}
          />
        </div>
      </Card>

      <Card
        title="插件市场"
        className="pacc-glass-md"
        extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>共 {marketTotal} 个条目</span>}
      >
        <div ref={marketRef}>
          <Table<PluginMarketRow>
            rowKey={(r, i) => r.plugin_id ?? `mk-${i}`}
            columns={marketColumns}
            dataSource={market}
            scroll={{ x: 1200 }}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            locale={{ emptyText: '插件市场暂无条目' }}
          />
        </div>
      </Card>

      <Modal
        title={`加载插件「${loadTarget?.name || loadTarget?.id || ''}」`}
        open={loadTarget !== null}
        onCancel={() => setLoadTarget(null)}
        onOk={() => void confirmLoad()}
        confirmLoading={loadBusy}
        okText="确认加载"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        destroyOnHidden
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="确认要执行该插件的字节码吗？"
          description="加载后插件将获得其声明 API 的访问权限。留空类路径则回退到市场条目的安装包。"
        />
        <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>类路径 / 安装包覆盖（可选）</div>
        <Input
          value={loadPath}
          onChange={(e) => setLoadPath(e.target.value)}
          placeholder="例如：file:/opt/pacc/plugins/foo.jar 或留空使用市场包"
          maxLength={400}
        />
      </Modal>
    </div>
  )
}

/** 声明 API 超过 4 个时的「+N」标签（用原生 title 展示余量，避免额外的浮层开销）。 */
function MoreApisTag({ apis }: { apis: string[] }) {
  return (
    <Tag className="mono" style={{ fontSize: 11.5 }} title={apis.slice(4).join(', ')}>
      +{apis.length - 4}
    </Tag>
  )
}