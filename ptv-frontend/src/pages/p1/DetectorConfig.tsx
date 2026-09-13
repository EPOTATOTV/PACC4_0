import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Switch, Table, Tag, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { DetectorConfigRow } from '../../types'

export default function DetectorConfig() {
  const [rows, setRows] = useState<DetectorConfigRow[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [meta, setMeta] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.p1.detectorConfig()
      const list = (d.detectors?.length ? d.detectors : d.catalog) ?? []
      setRows(list)
      const m: Record<string, string> = {}
      for (const r of list) m[r.detector_key] = r.meta ?? ''
      setMeta(m)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function toggle(r: DetectorConfigRow, enabled: boolean) {
    try {
      const saved = await api.p1.saveDetectorConfig({ detector_key: r.detector_key, name: r.name, enabled, meta: meta[r.detector_key] ?? '' })
      setRows((prev) => prev.map((x) => (x.detector_key === r.detector_key ? saved : x)))
      message.success(`${r.name} 已${enabled ? '启用' : '停用'}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function saveMeta(r: DetectorConfigRow) {
    setSaving(r.detector_key)
    try {
      await api.p1.saveDetectorConfig({ detector_key: r.detector_key, name: r.name, enabled: r.enabled, meta: meta[r.detector_key] ?? '' })
      message.success('参数已保存')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving('')
    }
  }

  const columns: TableColumnsType<DetectorConfigRow> = [
    { title: '检测器', dataIndex: 'name', width: 160, render: (v: string, r) => <span>{v} <Tag>{r.detector_key}</Tag></span> },
    {
      title: '启停', dataIndex: 'enabled', width: 90,
      render: (v: boolean, r) => <Switch size="small" checked={v} onChange={(c) => void toggle(r, c)} />,
    },
    {
      title: '阈值 / 参数', dataIndex: 'meta', width: 220,
      render: (_, r) => (
        <Input
          size="small"
          value={meta[r.detector_key] ?? ''}
          placeholder="JSON 参数"
          onChange={(e) => setMeta((m) => ({ ...m, [r.detector_key]: e.target.value }))}
        />
      ),
    },
    {
      title: '', width: 90,
      render: (_, r) => (
        <Button size="small" loading={saving === r.detector_key} onClick={() => void saveMeta(r)}>保存</Button>
      ),
    },
    { title: '配置状态', dataIndex: 'configured', width: 100, render: (v: boolean) => (v ? <Tag color="green">已配置</Tag> : <Tag>默认</Tag>) },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>检测配置中心</h3>
        <div style={{ flex: 1 }} />
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>
      <Card styles={{ body: { padding: 0 } }}>
        <Table<DetectorConfigRow> rowKey="detector_key" columns={columns} dataSource={rows} loading={loading} pagination={false} scroll={{ x: 780 }} locale={{ emptyText: '暂无检测器配置' }} />
      </Card>
    </div>
  )
}