import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, Button, Card, Descriptions, Drawer, Input, Progress, Select, Slider, Space, Table, Tag, Tooltip,
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  DownloadOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined, SearchOutlined,
} from '@ant-design/icons'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import { api } from '../../api/client'
import type { V52ReplayRow } from '../../types'
import { useModalReveal, useTableRowReveal } from '../../hooks/useGSAP'

function fmt(ts: string) {
  return ts ? ts.replace('T', ' ').slice(0, 19) : '-'
}

function humanSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function duration(ms: number) {
  if (!ms) return '-'
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** 触发一次同源下载：管理端会话 cookie 由浏览器自动携带。 */
function download(id: string) {
  const a = document.createElement('a')
  a.href = api.v52.replay.downloadUrl(id)
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

export default function ReplayManager() {
  const [rows, setRows] = useState<V52ReplayRow[]>([])
  const [retention, setRetention] = useState(30)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const [pteid, setPteid] = useState('')
  const [applied, setApplied] = useState('')
  const [limit, setLimit] = useState(20)
  const [detail, setDetail] = useState<V52ReplayRow | null>(null)
  const [preview, setPreview] = useState<V52ReplayRow | null>(null)
  const [frameIndex, setFrameIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [frameUrls, setFrameUrls] = useState<Record<number, string>>({})
  const [imgErr, setImgErr] = useState(false)
  // 取回的帧以 blob URL 留在内存里：滑杆回拖、暂停重播都不再打后端
  const frameCache = useRef<Record<number, string>>({})

  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -16 })
  const revealModal = useModalReveal()

  const openPreview = useCallback((row: V52ReplayRow) => {
    frameCache.current = {}
    setFrameIndex(0)
    setPlaying(false)
    setFrameUrls({})
    setImgErr(false)
    setPreview(row)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await api.v52.replay.list(applied || undefined, limit)
      setRows(d.items ?? [])
      setRetention(d.retention_days ?? 30)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [applied, limit])

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

  // 打开预览后按顺序把各帧拉回内存；逐帧抽帧要现场解密整个文件，串行推进免得打满后端
  useEffect(() => {
    if (!preview) return
    const total = preview.frames || 0
    let cancelled = false
    void (async () => {
      for (let i = 0; i < total && !cancelled; i++) {
        try {
          const res = await fetch(api.v53.replayFrameUrl(preview.id, i))
          if (cancelled) return
          // 取帧失败（录像过期 / 帧损坏）就直接停掉预取，画面回落到 <img> 直连，由它自己报错
          if (!res.ok) return
          const blob = await res.blob()
          if (cancelled) return
          const url = URL.createObjectURL(blob)
          frameCache.current[i] = url
          setFrameUrls((m) => ({ ...m, [i]: url }))
        } catch {
          return
        }
      }
    })()
    return () => { cancelled = true }
  }, [preview])

  // 关闭或切换录像时释放 blob，避免管理端长时间挂着几百帧内存
  useEffect(() => {
    if (!preview) return
    return () => {
      Object.values(frameCache.current).forEach((url) => URL.revokeObjectURL(url))
      frameCache.current = {}
    }
  }, [preview])

  useEffect(() => {
    if (!playing || !preview) return
    const total = preview.frames || 0
    if (total <= 1) return
    const step = Math.max(80, Math.round(1000 / Math.max(preview.fps || 5, 1)))
    const timer = window.setInterval(() => {
      setFrameIndex((i) => {
        if (i + 1 >= total) {
          setPlaying(false)
          return i
        }
        return i + 1
      })
    }, step)
    return () => window.clearInterval(timer)
  }, [playing, preview])

  const stats = useMemo(() => {
    const total = rows.reduce((s, r) => s + (r.size_bytes ?? 0), 0)
    return { total, latest: rows[0]?.created_at }
  }, [rows])

  const columns: TableColumnsType<V52ReplayRow> = [
    { title: 'PTEID', dataIndex: 'pteid', width: 150, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    {
      title: '关联告警', dataIndex: 'alert_id', width: 160,
      render: (v: string) => (v ? <Tag color="volcano">{v.slice(0, 12)}</Tag> : <span style={{ color: 'var(--muted)' }}>无</span>),
    },
    { title: '时长', dataIndex: 'duration_ms', width: 80, render: (v: number) => duration(v) },
    { title: '分辨率', width: 110, render: (_, r) => (r.width && r.height ? `${r.width}×${r.height}` : '-') },
    { title: '帧数', dataIndex: 'frames', width: 80 },
    { title: 'FPS', dataIndex: 'fps', width: 70, render: (v: number) => v || '-' },
    { title: '大小', dataIndex: 'size_bytes', width: 100, render: (v: number) => humanSize(v) },
    { title: '录像时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
    { title: '过期时间', dataIndex: 'expires_at', width: 170, render: (v: string) => fmt(v) },
    {
      title: '操作', width: 210, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Tooltip title={r.frames ? '逐帧播放，画面带操作者水印' : '该录像没有帧，无法预览'}>
            <Button size="small" type="primary" ghost disabled={!r.frames} onClick={() => openPreview(r)}>预览</Button>
          </Tooltip>
          <Button size="small" type="text" onClick={() => setDetail(r)}>详情</Button>
          <Button size="small" icon={<DownloadOutlined />} onClick={() => download(r.id)}>下载</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="查端回放管理"
        description={`取证录像元数据与解密下载 · 保留 ${retention} 天后自动清理`}
        error={err}
        onCloseError={() => setErr('')}
        extra={
          <Space>
            <Input
              placeholder="按 PTEID 过滤"
              value={pteid}
              onChange={(e) => setPteid(e.target.value)}
              onPressEnter={() => setApplied(pteid.trim())}
              style={{ width: 190 }}
              allowClear
            />
            <Select
              value={limit}
              onChange={setLimit}
              style={{ width: 116 }}
              options={[20, 50, 100].map((n) => ({ label: `最近 ${n} 条`, value: n }))}
            />
            <Button icon={<SearchOutlined />} onClick={() => setApplied(pteid.trim())}>筛选</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
          </Space>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="录像条数" value={rows.length} hint={applied ? `已按 ${applied} 过滤` : '全部 PTEID'} />
        <MetricCard label="列表总大小" value={humanSize(stats.total)} accent="#4096ff" hint="加密后落盘体积" />
        <MetricCard label="保留期" value={`${retention} 天`} accent="#ffa940" hint="每日 03:40 清理过期录像" />
        <MetricCard label="最近录像" value={fmt(stats.latest).slice(5)} accent="#52c41a" />
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 14 }}
        message="录像为 AES-256-GCM 加密的 MJPEG-AVI，公开接口只提供解密下载，不返回密钥"
        description="列表里的「预览」由服务端现场解密并在每帧烧录操作者水印（操作者 · 录像 ID · 时间），浏览器直接看 JPEG 逐帧播放；需要原画质或离线留证时再下载后用本地播放器打开，下载动作会记入服务端日志。"
      />

      <Card styles={{ body: { padding: 0 } }} className="pacc-glass-md">
        <div ref={tableRef}>
          <Table<V52ReplayRow>
            rowKey="id"
            columns={columns}
            dataSource={rows}
            loading={loading}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            scroll={{ x: 1290 }}
            locale={{ emptyText: '暂无查端录像；客户端在红屏取证时自动上传' }}
          />
        </div>
      </Card>

      <Drawer
        title="回放详情"
        open={!!detail}
        onClose={() => setDetail(null)}
        width={480}
        extra={detail && (
          <Space>
            <Button icon={<PlayCircleOutlined />} disabled={!detail.frames} onClick={() => openPreview(detail)}>在线预览</Button>
            <Button type="primary" icon={<DownloadOutlined />} onClick={() => download(detail.id)}>下载明文</Button>
          </Space>
        )}
      >
        {detail && (
          <Descriptions
            column={1}
            size="small"
            bordered
            items={[
              { key: 'id', label: '录像 ID', children: detail.id },
              { key: 'pteid', label: 'PTEID', children: detail.pteid },
              { key: 'alert', label: '关联告警', children: detail.alert_id || '无' },
              { key: 'created', label: '录像时间', children: fmt(detail.created_at) },
              { key: 'expires', label: '过期时间', children: fmt(detail.expires_at) },
              { key: 'dur', label: '时长', children: duration(detail.duration_ms) },
              { key: 'res', label: '分辨率', children: detail.width && detail.height ? `${detail.width}×${detail.height}` : '-' },
              { key: 'frames', label: '帧数 / FPS', children: `${detail.frames} 帧 · ${detail.fps || '-'} fps` },
              { key: 'size', label: '加密体积', children: humanSize(detail.size_bytes) },
              { key: 'fmt', label: '容器格式', children: 'MJPEG-AVI（AES-256-GCM 加密）' },
              {
                key: 'sha', label: 'SHA256',
                children: <span style={{ fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all' }}>{detail.sha256 || '-'}</span>,
              },
            ]}
          />
        )}
      </Drawer>

      <Drawer
        title="在线预览"
        open={!!preview}
        onClose={() => { setPlaying(false); setPreview(null) }}
        width={720}
        extra={preview && (
          <Space>
            <Button
              type="primary"
              icon={playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
              disabled={(preview.frames || 0) <= 1}
              onClick={() => setPlaying((p) => !p)}
            >
              {playing ? '暂停' : '播放'}
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => download(preview.id)}>下载明文</Button>
          </Space>
        )}
      >
        {preview && (
          <>
            <div
              style={{
                background: '#000', borderRadius: 9, overflow: 'hidden',
                aspectRatio: preview.width && preview.height ? `${preview.width} / ${preview.height}` : '16 / 9',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}
            >
              <img
                key={`${preview.id}-${frameIndex}`}
                src={frameUrls[frameIndex] ?? api.v53.replayFrameUrl(preview.id, frameIndex)}
                alt={`第 ${frameIndex + 1} 帧`}
                onLoad={() => setImgErr(false)}
                onError={() => setImgErr(true)}
                style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }}
              />
            </div>

            {imgErr && (
              <Alert
                type="warning"
                showIcon
                style={{ marginTop: 11 }}
                message="这一帧取不到"
                description="录像可能已过期被清理，或该帧在客户端上传时就已损坏。"
              />
            )}

            <div style={{ marginTop: 13 }}>
              <Slider
                min={0}
                max={Math.max((preview.frames || 1) - 1, 0)}
                value={frameIndex}
                disabled={(preview.frames || 0) <= 1}
                tooltip={{ formatter: (v) => `第 ${(v ?? 0) + 1} 帧` }}
                onChange={(v) => { setPlaying(false); setFrameIndex(v) }}
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--muted)' }}>
                <span>
                  第 {frameIndex + 1} / {preview.frames} 帧 · 约 {duration(Math.round((frameIndex / Math.max(preview.fps || 5, 1)) * 1000))}
                </span>
                <span>{preview.fps || 5} fps · 边播边取</span>
              </div>
            </div>

            <div style={{ marginTop: 10 }}>
              <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>
                已取回 {Object.keys(frameUrls).length} / {preview.frames} 帧（关掉预览即释放内存，不会把明文留在浏览器缓存里）
              </div>
              <Progress
                percent={preview.frames ? Math.round((Object.keys(frameUrls).length / preview.frames) * 100) : 0}
                size="small"
                showInfo={false}
                strokeColor="#4096ff"
              />
            </div>

            <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 14, lineHeight: 1.7 }}>
              画面上的斜排水印由服务端在解密后逐帧烧录，带操作者、录像 ID 与时间；接口只回 JPEG，不回密钥也不写明文文件。
              逐帧取图要对整份录像现场解密，所以帧多时前面的缓冲会慢一些，播放中若某帧还没到会直接回源，可能轻微卡顿。
            </div>
          </>
        )}
      </Drawer>
    </div>
  )
}