import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Segmented, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { RedscreenAlert } from '../types'
import { StatusPill } from '../components/StatusPill'
import { RedScreenOverlay } from '../components/animations'
import { useTableRowReveal } from '../hooks/useGSAP'

const { Title, Text } = Typography

export default function Redscreen() {
  const [state, setState] = useState('PENDING_INSPECT')
  const [list, setList] = useState<RedscreenAlert[]>([])
  const [err, setErr] = useState('')
  const [preview, setPreview] = useState<RedscreenAlert | null>(null)
  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -18 })

  const load = useCallback(async (s: string) => {
    try {
      setList(await api.redscreens.list(s))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    load(state)
  }, [state, load])

  // 列表换了（切换状态或刷新）就重播一次行入场，等 React 把行渲染进 DOM 后再触发
  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [list, reveal])

  const states = ['PENDING_INSPECT', 'CONFIRMED', 'FALSE_POSITIVE']

  const columns: TableColumnsType<RedscreenAlert> = [
    { title: '警告 ID', dataIndex: 'alertId' },
    { title: '级别', dataIndex: 'level', width: 90 },
    { title: '作弊类型', dataIndex: 'cheatType' },
    { title: '玩家', dataIndex: 'pteidMasked' },
    { title: '版本', dataIndex: 'edition', width: 110 },
    { title: '风险分', dataIndex: 'riskScore', width: 90 },
    { title: '广播/送达', dataIndex: 'broadcastOnline', width: 110, render: (_, a) => `${a.broadcastOnline}/${a.broadcastAck}` },
    { title: '时间', dataIndex: 'occurredAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    { title: '状态', dataIndex: 'state', width: 130, render: (v: RedscreenAlert['state']) => <StatusPill value={v} /> },
    {
      title: '操作', dataIndex: 'alertId', width: 100,
      render: (_, a) => <Button size="small" onClick={() => setPreview(a)}>预览红屏</Button>,
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>红屏管理</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>玩家端红屏警告 · 待查 / 确认 / 误报</Text>
        <div style={{ flex: 1 }} />
        <Segmented
          value={state}
          onChange={(v) => setState(v as string)}
          options={states}
        />
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card styles={{ body: { padding: 0 } }}>
        <div ref={tableRef}>
          <Table<RedscreenAlert>
            rowKey="alertId"
            columns={columns}
            dataSource={list}
            pagination={{ pageSize: 15, hideOnSinglePage: true }}
            scroll={{ x: 760 }}
            locale={{ emptyText: `暂无 ${state} 记录` }}
          />
        </div>
      </Card>

      {/* 红屏回放：按当前动效模板重播玩家端看到的警告序列 */}
      <RedScreenOverlay
        open={!!preview}
        title="检测到作弊行为"
        details={
          preview
            ? [
                { label: '警告 ID', value: preview.alertId },
                { label: '作弊类型', value: preview.cheatType },
                { label: '玩家', value: preview.pteidMasked },
                { label: '客户端版本', value: preview.edition },
                { label: '风险分', value: String(preview.riskScore) },
                {
                  label: '触发时间',
                  value: preview.occurredAt ? new Date(preview.occurredAt).toLocaleString('zh-CN', { hour12: false }) : '-',
                },
              ]
            : []
        }
        onClose={() => setPreview(null)}
      />
    </div>
  )
}