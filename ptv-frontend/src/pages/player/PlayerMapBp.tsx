import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Modal, Progress, Tag, Typography } from 'antd'
import { api } from '../../api/client'
import type { BpStateDto, MapEntry } from '../../types'
import { useWebSocket } from '../../ws/useWebSocket'

const { Title, Text } = Typography

const BLUE = '#58a6ff'
const RED = '#ff3b30'
const GREEN = '#3fb950'
const AMBER = '#d29922'

const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }

export default function PlayerMapBp() {
  const { bpId = '' } = useParams()
  const [state, setState] = useState<BpStateDto | null>(null)
  const [entries, setEntries] = useState<MapEntry[]>([])
  const [err, setErr] = useState('')
  const [confirm, setConfirm] = useState<{ map: MapEntry; action: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const [now, setNow] = useState(() => Date.now())
  const timerRef = useRef<number | null>(null)
  useEffect(() => {
    timerRef.current = window.setInterval(() => setNow(Date.now()), 500)
    return () => { if (timerRef.current) window.clearInterval(timerRef.current) }
  }, [])

  const load = useCallback(async () => {
    if (!bpId) return
    try {
      const s = await api.player.maps.bpState(bpId)
      setState(s)
      if (s.pool_id) setEntries(await api.player.maps.entries(s.pool_id))
      else setEntries([])
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }, [bpId])
  useEffect(() => { load() }, [load])

  // 玩家端长连接订阅 BP 实时事件（走全局事件总线）
  const bpWsUrl = useMemo(
    () => `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/ptv`,
    [],
  )
  const { send: bpSend } = useWebSocket(bpWsUrl, {
    onOpen: () => {
      if (bpId) bpSend({ type: 'bp_subscribe', bp_session_id: bpId })
    },
    onMessage: () => { load() },
  })
  useEffect(() => {
    if (bpId) bpSend({ type: 'bp_subscribe', bp_session_id: bpId })
  }, [bpId, bpSend])

  const deadline = state?.turn_deadline ? new Date(state.turn_deadline).getTime() : null
  const remainMs = deadline ? Math.max(0, deadline - now) : 0
  const remainSec = Math.ceil(remainMs / 1000)
  const turnColor = remainSec > 10 ? GREEN : remainSec > 5 ? AMBER : RED

  const mySide = state?.my_side
  const myTurn = state?.can_act_for === mySide
  const action = state?.current_turn?.endsWith('_PICK') ? 'PICK' : 'BAN'
  const actionLabel = action === 'PICK' ? '选择' : '禁用'

  const usedBanIds = useMemo(() => new Set((state?.banned_maps ?? []).map((m) => m.map_id)), [state?.banned_maps])
  const usedPickIds = useMemo(() => new Set((state?.selected_maps ?? []).map((m) => m.map_id)), [state?.selected_maps])

  const myPicks = state?.selected_maps?.filter((m) => m.side === mySide) ?? []
  const oppPicks = state?.selected_maps?.filter((m) => m.side !== mySide) ?? []

  async function doAction() {
    const c = confirm
    if (!c || !myTurn || !mySide) return
    setBusy(true)
    try {
      await api.player.maps.bpAction(bpId, { action: c.action, map_id: c.map.mapId })
      setConfirm(null)
      load()
    } catch (e) { setErr((e as Error).message) }
    finally { setBusy(false) }
  }

  function tapMap(m: MapEntry) {
    if (!myTurn) return
    setConfirm({ map: m, action })
  }

  if (!state) {
    return <div style={{ color: 'var(--muted)', padding: 24 }}>加载中…{err && <Alert type="error" showIcon message={err} style={{ marginTop: 12 }} />}</div>
  }

  const completed = state.status === 'COMPLETED'

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <Title level={4} style={{ margin: 0 }}>地图 BP</Title>
        <Tag color="processing">{state.format}</Tag>
        <Tag bordered={false}>{statusNames[state.status] ?? state.status}</Tag>
        {state.status === 'ACTIVE' && myTurn && <Tag color="green">轮到你{actionLabel}地图</Tag>}
        {state.status === 'ACTIVE' && !myTurn && <Tag bordered={false}>等待对方…</Tag>}
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      {/* 对阵条 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <TeamLabel name={state.blue_team_name || '蓝方'} color={BLUE} highlight={state.can_act_for === 'BLUE'} you={mySide === 'BLUE'} />
        <Text type="secondary">vs</Text>
        <TeamLabel name={state.red_team_name || '红方'} color={RED} highlight={state.can_act_for === 'RED'} you={mySide === 'RED'} />
      </div>

      {/* 倒计时 */}
      {state.status === 'ACTIVE' && state.current_turn && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16, background: 'rgba(255,255,255,.03)', border: '1px solid var(--border)', borderRadius: 12, padding: '10px 16px', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 200, fontSize: 13 }}>
            <Text>本回合：<Text strong>{turnText(state.current_turn)}</Text></Text>
            {state.current_round && <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>第 {state.current_round}/{state.total_rounds} 轮</Text>}
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
            <span style={{ fontSize: 44, fontWeight: 800, color: turnColor, fontVariantNumeric: 'tabular-nums', lineHeight: 1 }}>{remainSec}</span>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>秒</span>
          </div>
        </div>
      )}

      {/* 按我的/对方已选分区 */}
      {!completed && (
        <div style={{ display: 'flex', gap: 14, marginBottom: 14, flexWrap: 'wrap' }}>
          <PickZone title={myTurn && mySide ? '你的选择' : '我方已选'} color={mySide === 'RED' ? RED : BLUE} items={myPicks} />
          <PickZone title="对方已选" color={mySide === 'RED' ? BLUE : RED} items={oppPicks} />
        </div>
      )}

      {/* 地图池 */}
      {completed ? (
        <DonePanel maps={state.selected_maps ?? []} />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))', gap: 12 }}>
          {entries.map((m) => {
            const banned = usedBanIds.has(m.mapId)
            const picked = usedPickIds.has(m.mapId)
            const pickedBy = state?.selected_maps?.find((x) => x.map_id === m.mapId)?.side
            const pickIdx = state?.selected_maps?.findIndex((x) => x.map_id === m.mapId)
            const enabled = myTurn && !banned && !picked
            return (
              <button
                key={m.mapId}
                disabled={!enabled}
                onClick={() => tapMap(m)}
                style={{
                  position: 'relative',
                  borderRadius: 12,
                  overflow: 'hidden',
                  border: '1px solid var(--border)',
                  background: 'rgba(255,255,255,.03)',
                  padding: 0,
                  cursor: enabled ? 'pointer' : 'default',
                  fontFamily: 'inherit',
                  textAlign: 'left',
                  minHeight: 84,
                  opacity: !enabled && !banned && !picked ? 0.8 : 1,
                }}
              >
                {m.thumbnailUrl ? (
                  <img src={m.thumbnailUrl} alt={m.name} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
                ) : (
                  <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(160deg,#1c2230,#0c0f14)' }} />
                )}
                {banned && <BanOverlay />}
                {picked && <PickOverlay color={pickedBy === 'RED' ? RED : BLUE} idx={(pickIdx ?? 0) + 1} />}
                <div style={{ position: 'absolute', insetInline: 0, insetBlockEnd: 0, padding: '5px 7px', background: 'linear-gradient(0deg, rgba(5,6,8,.9), transparent)', fontSize: 12, color: '#fff', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</div>
              </button>
            )
          })}
        </div>
      )}

      <div style={{ marginTop: 14 }}>
        {!myTurn && state.status === 'ACTIVE' && (
          <Text type="secondary" style={{ fontSize: 13 }}>当前为非你的回合，轻点地图无效；等待对方或系统裁决。</Text>
        )}
        {state.status === 'PAUSED' && <Alert type="warning" showIcon message="BP 已暂停，请等待裁判恢复。" />}
        {state.status === 'CANCELLED' && <Alert type="error" showIcon message={state.cancel_reason || '本场 BP 已被取消。'} />}
      </div>

      {/* 操作确认 */}
      <Modal
        title={confirm ? `确认${confirm.action === 'PICK' ? '选择' : '禁用'} ${confirm.map.name}？` : '确认操作'}
        open={!!confirm}
        okText="确认"
        cancelText="取消"
        okButtonProps={{ danger: confirm ? confirm.action === 'BAN' : false }}
        confirmLoading={busy}
        onCancel={() => setConfirm(null)}
        onOk={doAction}
      >
        <Text type="secondary">该操作会立即生效并推进回合，无法撤销。</Text>
      </Modal>
    </div>
  )
}

function TeamLabel({ name, color, highlight, you }: { name: string; color: string; highlight: boolean; you: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'rgba(255,255,255,.03)', border: `1px solid ${highlight ? color : 'var(--border)'}`, borderRadius: 10, padding: '8px 14px' }}>
      <span style={{ width: 10, height: 10, borderRadius: 3, background: color, boxShadow: highlight ? `0 0 10px ${color}` : 'none' }} />
      <Text strong style={{ color }}>{name}</Text>
      {you && <Tag bordered={false} color="success">你</Tag>}
      {highlight && <Tag bordered={false} color="warning">回合中</Tag>}
    </div>
  )
}

function PickZone({ title, color, items }: { title: string; color: string; items: { map_name: string; round: number }[] }) {
  return (
    <div style={{ flex: 1, minWidth: 220, background: 'rgba(255,255,255,.02)', border: '1px solid var(--border)', borderRadius: 12, padding: '10px 14px' }}>
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>{title}</Text>
      {items.length === 0 ? <Text type="secondary" style={{ fontSize: 12 }}>暂无</Text> : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {items.map((m, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
              <span style={{ width: 20, height: 20, borderRadius: 10, background: color, color: '#fff', fontSize: 11, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700 }}>{m.round}</span>
              <Text>{m.map_name}</Text>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function BanOverlay() {
  return (
    <div style={{ position: 'absolute', inset: 0, background: 'rgba(10,12,15,.72)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <span style={{ color: '#f85149', fontSize: 16, fontWeight: 800, transform: 'rotate(-18deg)', border: '2px solid #f85149', borderRadius: 8, padding: '2px 8px' }}>BAN</span>
    </div>
  )
}

function PickOverlay({ color, idx }: { color: string; idx: number }) {
  return (
    <div style={{ position: 'absolute', inset: 0, background: `${color}1f`, border: `2px solid ${color}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <span style={{ color: '#fff', fontSize: 16, fontWeight: 800, background: color, borderRadius: 999, width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{idx}</span>
    </div>
  )
}

function DonePanel({ maps }: { maps: { map_name: string; side: string; round: number }[] }) {
  const total = Math.max(1, maps.length)
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <Title level={5} style={{ margin: 0 }}>BP 完成</Title>
        <Tag color="success">最终选图</Tag>
      </div>
      {maps.length === 0 ? (
        <Text type="secondary">无最终选图。</Text>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Progress percent={100} showInfo={false} size={{ height: 6 }} strokeColor={{ '0%': BLUE, '100%': GREEN }} />
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 10 }}>
            {maps.map((m, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'rgba(255,255,255,.03)', border: '1px solid var(--border)', borderRadius: 10, padding: '8px 12px' }}>
                <span style={{ width: 26, height: 26, borderRadius: 13, background: m.side === 'RED' ? RED : BLUE, color: '#fff', fontSize: 12, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700 }}>{m.side === 'RED' ? 'R' : 'B'}</span>
                <div style={{ minWidth: 0 }}>
                  <Text strong style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.map_name}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>第 {m.round} 图</Text>
                </div>
              </div>
            ))}
          </div>
          <Text type="secondary" style={{ fontSize: 12 }}>最终 {total} 张选图已写入对局，进入比赛吧。</Text>
        </div>
      )}
    </div>
  )
}

function turnText(t: string): string {
  const [side, action] = t.split('_')
  const s = side === 'BLUE' ? '蓝方' : '红方'
  return action === 'BAN' ? `${s} 禁用地图` : `${s} 选择地图`
}