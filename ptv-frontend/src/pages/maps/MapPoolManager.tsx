import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Empty, Input, Modal, Popconfirm, Select, Space, Tag, Typography, Switch, Grid, message } from 'antd'
import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, EditOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { MapEntry, MapPool, MapPoolStats } from '../../types'

const { Title, Text } = Typography
const { useBreakpoint } = Grid

const typeOptions = [
  { value: 'QUALIFIER', label: '资格赛' },
  { value: 'GROUP', label: '小组赛' },
  { value: 'KNOCKOUT', label: '淘汰赛' },
  { value: 'FINAL', label: '决赛' },
]

export default function MapPoolManager() {
  const screens = useBreakpoint()
  const [pools, setPools] = useState<MapPool[]>([])
  const [poolId, setPoolId] = useState('')
  const [entries, setEntries] = useState<MapEntry[]>([])
  const [stats, setStats] = useState<MapPoolStats | null>(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  // 新建池表单
  const [poolName, setPoolName] = useState('')
  const [poolOpen, setPoolOpen] = useState(false)

  // 地图编辑
  const [editing, setEditing] = useState<MapEntry | null>(null)

  const loadPools = useCallback(async () => {
    try {
      const list = await api.maps.pools()
      setPools(list)
      setPoolId((prev) => (list.some((p) => p.poolId === prev) ? prev : list[0]?.poolId ?? ''))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])
  useEffect(() => { loadPools() }, [loadPools])

  const loadEntries = useCallback(async () => {
    if (!poolId) { setEntries([]); setStats(null); return }
    try {
      const [list, st] = await Promise.all([
        api.maps.entries(poolId),
        api.maps.poolStats(poolId).catch(() => null),
      ])
      setEntries(list)
      setStats(st)
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }, [poolId])
  useEffect(() => { loadEntries() }, [poolId, loadEntries])

  async function createPool() {
    if (!poolName.trim()) return setErr('请填写地图池名称')
    try {
      await api.maps.createPool({ name: poolName.trim() })
      setPoolName('')
      setPoolOpen(false)
      loadPools()
    } catch (e) { setErr((e as Error).message) }
  }

  async function deletePool(p: MapPool) {
    try {
      await api.maps.deletePool(p.poolId)
      if (poolId === p.poolId) setPoolId('')
      loadPools()
    } catch (e) { setErr((e as Error).message) }
  }

  async function saveEntry() {
    const e = editing
    if (!e) return
    try {
      if (e.mapId) {
        await api.maps.updateEntry(e.mapId, {
          name: e.name,
          name_en: e.nameEn ?? '',
          map_type: e.mapType ?? '',
          author: e.author ?? '',
          version: e.version ?? '',
          difficulty: e.difficulty ?? '',
          thumbnail_url: e.thumbnailUrl ?? '',
          preview_images: e.previewImages ?? '',
          description: e.description ?? '',
          download_url: e.downloadUrl ?? '',
          order_no: e.orderNo,
          active: e.active,
        })
      } else {
        await api.maps.createEntry(e.poolId, {
          name: e.name,
          name_en: e.nameEn ?? '',
          map_type: e.mapType ?? '',
          author: e.author ?? '',
          version: e.version ?? '',
          difficulty: e.difficulty ?? '',
          thumbnail_url: e.thumbnailUrl ?? '',
          preview_images: e.previewImages ?? '',
          description: e.description ?? '',
          download_url: e.downloadUrl ?? '',
          order_no: String(e.orderNo),
        })
      }
      setEditing(null)
      loadEntries()
    } catch (ee) { setErr((ee as Error).message) }
  }

  async function deleteEntry(m: MapEntry) {
    try {
      await api.maps.deleteEntry(m.mapId)
      loadEntries()
    } catch (ee) { setErr((ee as Error).message) }
  }

  async function toggleEntry(m: MapEntry) {
    try {
      await api.maps.toggleEntry(m.mapId)
      loadEntries()
    } catch (ee) { setErr((ee as Error).message) }
  }

  /** 上移/下移：与相邻条目的 order_no 互换，对齐既有后端 updateEntry。 */
  async function swapOrder(idx: number, dir: -1 | 1) {
    const to = idx + dir
    const a = entries[idx]
    const b = entries[to]
    if (!a || !b) return
    setLoading(true)
    try {
      await Promise.all([
        api.maps.updateEntry(a.mapId, { order_no: b.orderNo, active: a.active }),
        api.maps.updateEntry(b.mapId, { order_no: a.orderNo, active: b.active }),
      ])
      loadEntries()
    } catch (ee) { setErr((ee as Error).message) }
    finally { setLoading(false) }
  }

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file || !poolId) return
    const text = await file.text()
    const rows = parseCsv(text)
    if (rows.length === 0) { setErr('CSV 未解析到有效行'); return }
    try {
      const r = await api.maps.batchAddEntries(poolId, { rows })
      message.success(`成功导入 ${r.added} 张地图`)
      loadEntries()
    } catch (ee) { setErr((ee as Error).message) }
    finally { e.target.value = '' }
  }

  const pool = pools.find((p) => p.poolId === poolId)

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>地图池管理</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      {/* 池级统计概览 */}
      {pool && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', marginBottom: 14, padding: '10px 14px', border: '1px solid var(--border)', borderRadius: 10, background: 'rgba(255,255,255,.02)', fontSize: 13 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>池「{pool.name}」</Text>
          {stats && (
            <>
              <Tag bordered={false}>共 {stats.total}</Tag>
              <Tag bordered={false} color="success">启用 {stats.active}</Tag>
              <Tag bordered={false} color="error">累计反 {stats.ban_total}</Tag>
              <Tag bordered={false} color="processing">累计选 {stats.pick_total}</Tag>
              {Object.entries(stats.by_type ?? {}).map(([k, v]) => (
                <Tag key={k} bordered={false} color="geekblue">{k} {v}</Tag>
              ))}
            </>
          )}
        </div>
      )}

      {/* 池选择 pill */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center', marginBottom: 18 }}>
        {pools.map((p) => {
          const active = p.poolId === poolId
          return (
            <button
              key={p.poolId}
              onClick={() => setPoolId(p.poolId)}
              style={{
                border: active ? '1px solid var(--kpi-blue)' : '1px solid var(--border-strong)',
                background: active ? 'rgba(88,166,255,.12)' : 'rgba(255,255,255,.03)',
                color: active ? 'var(--kpi-blue)' : 'var(--text)',
                borderRadius: 999,
                padding: '6px 16px',
                fontSize: 13,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {p.name}
              <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{p.mapCount}</Text>
            </button>
          )
        })}
        <Button icon={<PlusOutlined />} onClick={() => setPoolOpen(true)}>新建池</Button>
      </div>

      {/* 池操作与批量导入 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center', marginBottom: 16 }}>
        <input ref={fileRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={onFile} />
        <Button icon={<UploadOutlined />} disabled={!poolId} onClick={() => fileRef.current?.click()}>导入 CSV</Button>
        {pool && <Button danger type="text" onClick={() => Modal.confirm({ title: '删除地图池', content: `删除「${pool.name}」及其全部地图？`, okButtonProps: { danger: true }, onOk: () => deletePool(pool) })}>删除该池</Button>}
        {pool && <Text type="secondary" style={{ fontSize: 12 }}>CSV 列：name,name_en,map_type,author,version,difficulty,thumbnail_url,description,download_url</Text>}
      </div>

      {/* 地图卡片网格 */}
      {entries.length === 0 ? (
        <Card><Empty description={poolId ? '该池暂无地图，点击下方「新增地图」或导入 CSV。' : '请先选择或新建地图池。'} /></Card>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: screens.lg ? 'repeat(3, 1fr)' : screens.md ? 'repeat(2, 1fr)' : '1fr',
            gap: 14,
          }}
        >
          {entries.map((m, idx) => (
            <Card
              key={m.mapId}
              className="map-card"
              styles={{ body: { padding: 0 } }}
              style={{ background: 'rgba(255,255,255,.02)', border: m.active ? '1px solid var(--border)' : '1px dashed var(--border-strong)', opacity: m.active ? 1 : 0.55 }}
            >
              <div style={{ display: 'flex' }}>
                <div
                  style={{
                    width: 108,
                    flexShrink: 0,
                    alignSelf: 'stretch',
                    background: m.thumbnailUrl ? `url(${m.thumbnailUrl}) center/cover no-repeat` : 'linear-gradient(160deg,#1c2230,#0c0f14)',
                  }}
                />
                <div style={{ flex: 1, padding: '10px 12px 8px', minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ display: 'inline-block', minWidth: 24, textAlign: 'center', fontSize: 11, color: 'var(--muted)', border: '1px solid var(--border-strong)', borderRadius: 4, padding: '0 4px' }}>{m.orderNo}</span>
                    <Text strong ellipsis style={{ flex: 1, maxWidth: 180 }}>{m.name}</Text>
                    {m.mapType && <Tag bordered={false} color="geekblue">{m.mapType}</Tag>}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4, color: 'var(--muted)', fontSize: 12 }}>
                    {m.author && <span>{m.author}</span>}
                    {m.difficulty && <span>难度 {m.difficulty}</span>}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, fontSize: 12 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>胜率</Text>
                    <Tag color="blue" style={{ width: 52, textAlign: 'center', marginInlineEnd: 0, fontSize: 11 }}>蓝 {pct(m.winRateBlue)}%</Tag>
                    <div style={{ flex: 1, height: 4, borderRadius: 2, background: 'rgba(255,255,255,.06)', overflow: 'hidden' }}>
                      <div style={{ height: '100%', width: `${pct(m.winRateBlue)}%`, background: 'var(--kpi-blue)', borderRadius: 2 }} />
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4, fontSize: 12 }}>
                    <Text type="secondary" style={{ fontSize: 11, visibility: 'hidden' }}>胜率</Text>
                    <Tag color="red" style={{ width: 52, textAlign: 'center', marginInlineEnd: 0, fontSize: 11 }}>红 {pct(m.winRateRed)}%</Tag>
                    <div style={{ flex: 1, height: 4, borderRadius: 2, background: 'rgba(255,255,255,.06)', overflow: 'hidden' }}>
                      <div style={{ height: '100%', width: `${pct(m.winRateRed)}%`, background: 'var(--kpi-red)', borderRadius: 2 }} />
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                    <Tag bordered={false} color="error">反 {m.banCount}</Tag>
                    <Tag bordered={false} color="processing">选 {m.pickCount}</Tag>
                    <div style={{ flex: 1 }} />
                    <Switch size="small" checked={m.active} onChange={() => toggleEntry(m)} />
                    <Button size="small" type="text" icon={<ArrowUpOutlined />} disabled={idx === 0 || loading} onClick={() => swapOrder(idx, -1)} />
                    <Button size="small" type="text" icon={<ArrowDownOutlined />} disabled={idx === entries.length - 1 || loading} onClick={() => swapOrder(idx, 1)} />
                    <Button size="small" type="text" icon={<EditOutlined />} onClick={() => setEditing(m)} />
                    <Popconfirm title="删除该地图？" onConfirm={() => deleteEntry(m)}><Button size="small" type="text" danger icon={<DeleteOutlined />} /></Popconfirm>
                  </div>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Button type="primary" icon={<PlusOutlined />} disabled={!poolId} style={{ marginTop: 16 }} onClick={() => setEditing(blankEntry(poolId))}>新增地图</Button>

      {/* 新建池 Modal */}
      <Modal title="新建地图池" open={poolOpen} okText="创建" cancelText="取消" onOk={createPool} onCancel={() => setPoolOpen(false)}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input placeholder="地图池名称" value={poolName} onChange={(e) => setPoolName(e.target.value)} />
          <Text type="secondary" style={{ fontSize: 12 }}>赛事 ID 可留空＝通用池；创建后可在后续编辑（本版暂仅支持改名/停用）。</Text>
        </Space>
      </Modal>

      {/* 编辑地图 Modal */}
      <Modal
        title={editing?.nameEn || '新增地图'}
        open={!!editing}
        okText="保存"
        cancelText="取消"
        width={640}
        onOk={saveEntry}
        onCancel={() => setEditing(null)}
      >
        <EntryForm entry={editing} onChange={setEditing} />
      </Modal>
    </div>
  )
}

function EntryForm({ entry, onChange }: { entry: MapEntry | null; onChange: (e: MapEntry) => void }) {
  function patch(p: Partial<MapEntry>) {
    if (!entry) return
    onChange({ ...entry, ...p })
  }
  const row = (label: string, span: number, element: React.ReactNode) => ({ label, span, element })
  const fields = [
    row('名称 *', 24, <Input value={entry?.name} onChange={(e) => patch({ name: e.target.value })} placeholder="中文名" />),
    row('英文名', 24, <Input value={entry?.nameEn} onChange={(e) => patch({ nameEn: e.target.value })} placeholder="English name" />),
    row('地图类型', 8, <Select allowClear value={entry?.mapType} onChange={(v) => patch({ mapType: v })} options={typeOptions} placeholder="类型" />),
    row('作者', 8, <Input value={entry?.author} onChange={(e) => patch({ author: e.target.value })} />),
    row('难度', 8, <Input value={entry?.difficulty} onChange={(e) => patch({ difficulty: e.target.value })} />),
    row('版本', 8, <Input value={entry?.version} onChange={(e) => patch({ version: e.target.value })} />),
    row('缩略图 URL', 16, <Input value={entry?.thumbnailUrl} onChange={(e) => patch({ thumbnailUrl: e.target.value })} />),
    row('下载 URL', 24, <Input value={entry?.downloadUrl} onChange={(e) => patch({ downloadUrl: e.target.value })} />),
    row('预览图 URL（JSON 数组，可选）', 24, <Input value={entry?.previewImages} onChange={(e) => patch({ previewImages: e.target.value })} placeholder='["https://...", "..."]' />),
    row('描述', 24, <Input.TextArea rows={2} value={entry?.description} onChange={(e) => patch({ description: e.target.value })} />),
  ]
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '4px 14px' }}>
      {fields.map((f) => (
        <div key={f.label} style={{ gridColumn: `span ${f.span === 8 ? '1' : f.span === 16 ? '2' : '2'}` }}>
          <Text type="secondary" style={{ fontSize: 12 }}>{f.label}</Text>
          <div style={{ marginTop: 4, marginBottom: 10 }}>{f.element}</div>
        </div>
      ))}
    </div>
  )
}

function blankEntry(poolId: string): MapEntry {
  return { mapId: '', poolId, name: '', active: true, orderNo: 0, banCount: 0, pickCount: 0, winRateBlue: 0, winRateRed: 0, createdAt: '' }
}

function pct(v: number): number {
  return Math.round((v || 0) * 100)
}

/** 极简 CSV 解析：支持引号包裹与逗号内换行，跳过表头空行。 */
function parseCsv(text: string): Record<string, unknown>[] {
  const rows: string[][] = []
  let cur: string[] = []
  let field = ''
  let inQuote = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inQuote) {
      if (ch === '"') { if (text[i + 1] === '"') { field += '"'; i++ } else inQuote = false }
      else field += ch
    } else if (ch === '"') inQuote = true
    else if (ch === ',') { cur.push(field); field = '' }
    else if (ch === '\n') { cur.push(field); rows.push(cur); cur = []; field = '' }
    else field += ch
  }
  if (field.length || cur.length) { cur.push(field); rows.push(cur) }
  const header = rows[0]?.map((h) => h.trim()) ?? []
  const map: Record<string, string> = {
    name: 'name', name_en: 'name_en', map_type: 'map_type', author: 'author',
    version: 'version', difficulty: 'difficulty', thumbnail_url: 'thumbnail_url',
    description: 'description', download_url: 'download_url',
  }
  return rows.slice(1).filter((r) => r.some((c) => c.trim())).map((r) => {
    const out: Record<string, unknown> = {}
    header.forEach((h, i) => {
      const key = map[h]
      if (key) out[key] = r[i]?.trim()
    })
    return out
  })
}