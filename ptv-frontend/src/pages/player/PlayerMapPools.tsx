import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Empty, Grid, Modal, Progress, Tag, Typography } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { MapBanPickSession, MapEntry, MapPool } from '../../types'

const { Title, Text } = Typography
const { useBreakpoint } = Grid

const BLUE = '#58a6ff'
const RED = '#ff3b30'

export default function PlayerMapPools() {
  const screens = useBreakpoint()
  const navigate = useNavigate()
  const [pools, setPools] = useState<MapPool[]>([])
  const [poolId, setPoolId] = useState('')
  const [entries, setEntries] = useState<MapEntry[]>([])
  const [detail, setDetail] = useState<MapEntry | null>(null)
  const [currentBp, setCurrentBp] = useState<MapBanPickSession | null>(null)
  const [denied, setDenied] = useState(false)
  const [err, setErr] = useState('')

  const loadPools = useCallback(async () => {
    try {
      const list = await api.player.maps.pools()
      setPools(list)
      setPoolId((prev) => (list.some((p) => p.poolId === prev) ? prev : list[0]?.poolId ?? ''))
      setDenied(false)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])
  useEffect(() => { loadPools() }, [loadPools])

  const loadEntries = useCallback(async () => {
    if (!poolId) { setEntries([]); return }
    try {
      const list = await api.player.maps.entries(poolId)
      setEntries(list)
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }, [poolId])
  useEffect(() => { loadEntries() }, [poolId, loadEntries])

  const loadCurrentBp = useCallback(async () => {
    try {
      const list = await api.player.maps.bpCurrent()
      setCurrentBp(list[0] ?? null)
      setDenied(false)
    } catch {
      // 403 = 非 APPROVED 选手，授予只读浏览
      setDenied(true)
    }
  }, [])
  useEffect(() => { loadCurrentBp() }, [loadCurrentBp])

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>地图池</Title>

      {denied && (
        <Alert type="info" showIcon message="你尚未通过参赛审批，仅可浏览地图池，无法参与 BP。" style={{ marginBottom: 16 }} />
      )}
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      {currentBp && (
        <Card style={{ marginBottom: 16, background: 'rgba(88,166,255,.06)', borderColor: 'var(--kpi-blue)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <PlayCircleOutlined style={{ fontSize: 22, color: 'var(--kpi-blue)' }} />
            <div style={{ flex: 1, minWidth: 200 }}>
              <Text strong>你有进行中的 BP 比赛：</Text>
              <Text type="secondary" style={{ display: 'block', fontSize: 12 }}>{currentBp.blueTeamName} vs {currentBp.redTeamName} · {currentBp.format}</Text>
            </div>
            <Button type="primary" onClick={() => navigate(`/portal/maps/bp/${currentBp.bpSessionId}`)}>去参与</Button>
          </div>
        </Card>
      )}

      {/* 池选择 pill */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginBottom: 18 }}>
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
                padding: '7px 18px',
                fontSize: 14,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {p.name}
              <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{p.mapCount} 图</Text>
            </button>
          )
        })}
      </div>

      {entries.length === 0 ? (
        <Card><Empty description={poolId ? '该地图池暂无可用地图。' : '暂无地图池。'} /></Card>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: screens.lg ? 'repeat(4,1fr)' : screens.md ? 'repeat(3,1fr)' : screens.sm ? 'repeat(2,1fr)' : '1fr', gap: 14 }}>
          {entries.map((m) => (
            <button
              key={m.mapId}
              onClick={() => setDetail(m)}
              style={{
                position: 'relative',
                borderRadius: 14,
                overflow: 'hidden',
                border: '1px solid var(--border)',
                background: 'rgba(255,255,255,.02)',
                padding: 0,
                cursor: 'pointer',
                fontFamily: 'inherit',
                textAlign: 'left',
                minHeight: screens.sm ? 168 : 128,
              }}
            >
              {m.thumbnailUrl ? (
                <img src={m.thumbnailUrl} alt={m.name} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
              ) : (
                <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(160deg,#1c2230,#0c0f14)' }} />
              )}
              <div style={{ position: 'absolute', insetInlineStart: 0, insetBlockEnd: 0, insetInlineEnd: 0, padding: '8px 10px', background: 'linear-gradient(0deg, rgba(5,6,8,.92), transparent)' }}>
                <div style={{ fontWeight: 600, color: '#fff', fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</div>
                <div style={{ display: 'flex', gap: 6, marginTop: 4, fontSize: 11, color: '#c9d1d9' }}>
                  {m.author && <span>{m.author}</span>}
                  {m.mapType && <span>· {m.mapType}</span>}
                </div>
              </div>
              {!m.active && <div style={{ position: 'absolute', top: 8, right: 8 }}><Tag color="default">已下线</Tag></div>}
            </button>
          ))}
        </div>
      )}

      {/* 地图详情 */}
      <Modal open={!!detail} footer={null} onCancel={() => setDetail(null)} width={520} title={detail?.name}>
        {detail && (
          <div>
            {detail.thumbnailUrl && <img src={detail.thumbnailUrl} alt={detail.name} style={{ width: '100%', borderRadius: 10, maxHeight: 260, objectFit: 'cover', marginBottom: 12 }} />}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
              {detail.author && <Tag bordered={false}>作者 {detail.author}</Tag>}
              {detail.difficulty && <Tag bordered={false}>难度 {detail.difficulty}</Tag>}
              {detail.version && <Tag bordered={false}>v{detail.version}</Tag>}
              {detail.mapType && <Tag bordered={false}>{detail.mapType}</Tag>}
            </div>
            {detail.description && <Text style={{ display: 'block', marginBottom: 12 }}>{detail.description}</Text>}
            <div style={{ margin: 0 }}>
              <div style={{ display: 'flex', marginBottom: 4 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>历史胜负（蓝 / 红）</Text>
                <span style={{ flex: 1 }} />
                <Text style={{ fontSize: 12 }}>Ban {detail.banCount} · Pick {detail.pickCount}</Text>
              </div>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                <Tag color="blue" style={{ width: 44, textAlign: 'center', marginInlineEnd: 0 }}>蓝 {pct(detail.winRateBlue)}%</Tag>
                <Progress style={{ flex: 1, margin: 0 }} percent={pct(detail.winRateBlue)} showInfo={false} strokeColor={BLUE} size={{ height: 8 }} />
              </div>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 8 }}>
                <Tag color="red" style={{ width: 44, textAlign: 'center', marginInlineEnd: 0 }}>红 {pct(detail.winRateRed)}%</Tag>
                <Progress style={{ flex: 1, margin: 0 }} percent={pct(detail.winRateRed)} showInfo={false} strokeColor={RED} size={{ height: 8 }} />
              </div>
            </div>
            {detail.downloadUrl && (
              <Button block style={{ marginTop: 16 }} href={detail.downloadUrl} target="_blank" rel="noreferrer">下载地图</Button>
            )}
          </div>
        )}
      </Modal>
    </div>
  )
}

function pct(v: number): number {
  return Math.round((v || 0) * 100)
}