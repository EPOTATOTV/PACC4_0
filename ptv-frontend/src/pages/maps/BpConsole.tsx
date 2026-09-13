import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Modal, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined, SendOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { BpListedMap, BpStateDto, MapEntry } from '../../types'
import { useWebSocket } from '../../ws/useWebSocket'

const { Title, Text } = Typography

const BLUE = '#58a6ff'
const RED = '#ff3b30'

const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }
const statusColors: Record<string, string> = { PENDING: 'default', ACTIVE: 'processing', PAUSED: 'warning', COMPLETED: 'success', CANCELLED: 'error' }

export default function BpConsole() {
  const { bpId = '' } = useParams()
  const navigate = useNavigate()
  const [state, setState] = useState<BpStateDto | null>(null)
  const [entries, setEntries] = useState<MapEntry[]>([])
  const [err, setErr] = useState('')
  const [forceMap, setForceMap] = useState<{ action: 'BAN' | 'PICK'; map?: MapEntry } | null>(null)

  // 倒计时
  const [now, setNow] = useState(() => Date.now())
  const timerRef = useRef<number | null>(null)
  useEffect(() => {
    timerRef.current = window.setInterval(() => setNow(Date.now()), 500)
    return () => { if (timerRef.current) window.clearInterval(timerRef.current) }
  }, [])

  const load = useCallback(async () => {
    if (!bpId) return
    try {
      const s = await api.maps.bpState(bpId)
      setState(s)
      if (s.pool_id) setEntries(await api.maps.entries(s.pool_id))
      else setEntries([])
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }, [bpId])
  useEffect(() => { load() }, [load])

  // WebSocket 实时订阅（走全局事件总线：自动重连 + 心跳）
  const bpWsUrl = useMemo(
    () => `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/admin`,
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
  const turnColor = remainSec > 10 ? '#3fb950' : remainSec > 5 ? '#d29922' : '#ff3b30'

  const usedBanIds = useMemo(() => new Set((state?.banned_maps ?? []).map((m) => m.map_id)), [state?.banned_maps])
  const usedPickIds = useMemo(() => new Set((state?.selected_maps ?? []).map((m) => m.map_id)), [state?.selected_maps])

  async function run(action: string, body: Record<string, string | number> = {}) {
    try {
      await api.maps.bpForceAction(bpId, { action, operator: 'admin', ...body })
      setForceMap(null)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function lifecycle(fn: (id: string) => Promise<unknown>) {
    try { await fn(bpId); load() } catch (e) { setErr((e as Error).message) }
  }

  if (!state) {
    return <div style={{ color: 'var(--muted)', padding: 24 }}>加载中…{err && <Alert type="error" showIcon message={err} style={{ marginTop: 12 }} />}</div>
  }

  const currentSide = state.can_act_for
  const isBlueTurn = currentSide === 'BLUE'
  const isRedTurn = currentSide === 'RED'

  return (
    <div>
      {/* 顶栏 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', marginBottom: 16 }}>
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/maps/bp')}>返回</Button>
        <Title level={4} style={{ margin: 0 }}>BP 控制台</Title>
        <Tag color={statusColors[state.status]}>{statusNames[state.status] ?? state.status}</Tag>
        <Tag bordered={false}>{state.format}</Tag>
        {state.match_id && <Tag bordered={false} color="geekblue">对局 {short(state.match_id)}</Tag>}
        <div style={{ flex: 1 }} />
        {state.status === 'PENDING' && (
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => lifecycle(api.maps.bpStart)}>开始</Button>
        )}
        {state.status === 'ACTIVE' && (
          <Button icon={<PauseCircleOutlined />} onClick={() => lifecycle(api.maps.bpPause)}>暂停</Button>
        )}
        {state.status === 'PAUSED' && (
          <Button icon={<PlayCircleOutlined />} onClick={() => lifecycle(api.maps.bpResume)}>恢复</Button>
        )}
        {(state.status === 'ACTIVE' || state.status === 'PAUSED') && (
          <>
            <Button danger icon={<SendOutlined />} onClick={() => setForceMap({ action: 'BAN' })}>强制 Ban</Button>
            <Button type="primary" danger icon={<SendOutlined />} onClick={() => setForceMap({ action: 'PICK' })}>强制 Pick</Button>
          </>
        )}
        {state.status === 'ACTIVE' && (
          <Button type="primary" style={{ background: '#3fb950', borderColor: 'transparent' }} onClick={() => lifecycle(api.maps.bpComplete)}>完成</Button>
        )}
        {state.status !== 'COMPLETED' && state.status !== 'CANCELLED' && (
          <Button icon={<ReloadOutlined />} onClick={() => Modal.confirm({ title: '重置 BP', content: '清空全部操作并回到待开始？', okButtonProps: { danger: true }, onOk: () => lifecycle(api.maps.bpReset) })}>重置</Button>
        )}
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      {/* 回合条 */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 18, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 220 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 6 }}>
            <Text type="secondary">回合进度</Text>
            <Text type="secondary">{state.turn_index} 步 · {state.current_round}/{state.total_rounds} 轮</Text>
          </div>
          <div style={{ height: 6, borderRadius: 3, background: 'rgba(255,255,255,.06)', overflow: 'hidden' }}>
            <div style={{ height: '100%', borderRadius: 3, background: 'linear-gradient(90deg, var(--kpi-blue), var(--kpi-green))', width: `${Math.min(100, (state.turn_index / totalSteps(state.format)) * 100)}%`, transition: 'width .3s' }} />
          </div>
        </div>
        {state.status === 'ACTIVE' && state.current_turn && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'rgba(255,255,255,.04)', border: '1px solid var(--border)', borderRadius: 10, padding: '8px 14px' }}>
            <span style={{ width: 8, height: 8, borderRadius: 4, background: turnColor, boxShadow: `0 0 8px ${turnColor}` }} />
            <Text strong>{turnText(state.current_turn)}</Text>
            <span style={{ fontSize: 20, fontWeight: 700, color: turnColor, fontVariantNumeric: 'tabular-nums' }}>{remainSec}s</span>
          </div>
        )}
      </div>

      {/* 双阵营面板 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(200px, 1fr) minmax(300px, 2.2fr) minmax(200px, 1fr)', gap: 14, alignItems: 'start' }}>
        {/* 蓝方 */}
        <SidePanel
          name={state.blue_team_name || '蓝方'}
          color={BLUE}
          active={isBlueTurn}
          maps={state.selected_maps?.filter((m) => m.side === 'BLUE') ?? []}
          banned={state.banned_maps?.filter((m) => m.side === 'BLUE') ?? []}
        />
        {/* 地图池 */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(132px, 1fr))', gap: 12 }}>
          {entries.map((m) => {
            const banned = usedBanIds.has(m.mapId)
            const picked = usedPickIds.has(m.mapId)
            const pickedBy = state?.selected_maps?.find((x) => x.map_id === m.mapId)?.side
            const pickIdx = state?.selected_maps?.findIndex((x) => x.map_id === m.mapId)
            const clickable = state.status === 'ACTIVE' && !banned && !picked && !!state.can_act_for
            return (
              <button
                key={m.mapId}
                disabled={!clickable}
                onClick={() => {
                  if (state.current_turn) setForceMap({ action: state.current_turn.endsWith('_PICK') ? 'PICK' : 'BAN', map: m })
                }}
                style={{
                  position: 'relative',
                  borderRadius: 12,
                  overflow: 'hidden',
                  border: '1px solid var(--border)',
                  background: 'rgba(255,255,255,.03)',
                  padding: 0,
                  cursor: clickable ? 'pointer' : 'default',
                  fontFamily: 'inherit',
                  textAlign: 'left',
                  aspectRatio: '4 / 3',
                  outline: 'none',
                  boxShadow: clickable ? '0 0 0 1px rgba(255,255,255,.15)' : 'none',
                }}
              >
                {m.thumbnailUrl ? (
                  <img src={m.thumbnailUrl} alt={m.name} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
                ) : (
                  <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(160deg,#1c2230,#0c0f14)' }} />
                )}
                {banned && <div style={{ position: 'absolute', inset: 0, background: 'rgba(10,12,15,.72)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><span style={{ color: '#f85149', fontSize: 22, fontWeight: 800, transform: 'rotate(-18deg)', border: '2px solid #f85149', borderRadius: 8, padding: '2px 10px' }}>BAN</span></div>}
                {picked && <div style={{ position: 'absolute', inset: 0, background: `${pickedBy === 'RED' ? 'rgba(255,59,48,.18)' : 'rgba(88,166,255,.18)'}`, border: `2px solid ${pickedBy === 'RED' ? RED : BLUE}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><span style={{ color: '#fff', fontSize: 18, fontWeight: 800, background: pickedBy === 'RED' ? RED : BLUE, borderRadius: 999, width: 34, height: 34, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{pickIdx !== undefined && pickIdx >= 0 ? pickIdx + 1 : ''}</span></div>}
                <div style={{ position: 'absolute', insetInlineStart: 0, insetBlockEnd: 0, insetInlineEnd: 0, padding: '6px 8px', background: 'linear-gradient(0deg, rgba(5,6,8,.9), transparent)', fontSize: 12, color: '#fff' }}>
                  <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</div>
                </div>
              </button>
            )
          })}
        </div>
        {/* 红方 */}
        <SidePanel
          name={state.red_team_name || '红方'}
          color={RED}
          active={isRedTurn}
          maps={state.selected_maps?.filter((m) => m.side === 'RED') ?? []}
          banned={state.banned_maps?.filter((m) => m.side === 'RED') ?? []}
        />
      </div>

      {/* 操作时间线 */}
      <CardBlock title="操作记录">
        {(state.actions?.length ?? 0) === 0 ? (
          <Text type="secondary" style={{ fontSize: 12 }}>尚无操作。开始 BP 后双方选手或裁判将在此留下记录。</Text>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[...(state.actions ?? [])].reverse().map((a) => (
              <div key={a.actionId} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13, padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                <Tag bordered={false} color={a.team === 'RED' ? 'error' : 'processing'}>{a.team === 'RED' ? '红' : '蓝'}</Tag>
                <Text>{a.actionType === 'BAN' ? '禁用' : '选择'} <Text strong>{a.mapName}</Text></Text>
                {a.timeout && <Tag bordered={false} color="warning">超时</Tag>}
                <span style={{ flex: 1 }} />
                <Text type="secondary" style={{ fontSize: 11 }}>{a.operatorPteid ? short(a.operatorPteid) : '-'} · {fmt(a.createdAt)}</Text>
              </div>
            ))}
          </div>
        )}
      </CardBlock>

      {/* 强制操作 Modal */}
      <Modal
        title={`裁判强制${forceMap?.action === 'BAN' ? '禁用' : '选择'}`}
        open={!!forceMap}
        okText="确认"
        cancelText="取消"
        okButtonProps={{ danger: forceMap?.action === 'BAN', disabled: !forceMap?.map }}
        onCancel={() => setForceMap(null)}
        onOk={() => { if (forceMap) run(forceMap.action, forceMap.map ? { map_id: forceMap.map.mapId } : {}) }}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          {forceMap?.map
            ? <>将对 <Text strong style={{ color: 'var(--kpi-blue)' }}>{forceMap.map.name}</Text> 执行{forceMap.action === 'BAN' ? '禁用' : '选择'}。</>
            : <>不指定地图则系统随机选择一张合法地图。下方点击直接落子。</>}
        </Text>
        {forceMap?.map && (
          <Text type="secondary" style={{ display: 'block', fontSize: 12, marginBottom: 12 }}>
            如需改为随机落子，请取消本弹窗后点击顶栏按钮。
          </Text>
        )}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(120px, 1fr))', gap: 8, maxHeight: 320, overflowY: 'auto' }}>
          {entries.filter((m) => !usedBanIds.has(m.mapId) && !usedPickIds.has(m.mapId)).map((m) => (
            <button key={m.mapId} onClick={() => setForceMap((prev) => ({ action: prev?.action ?? 'PICK', map: m }))}
              style={{
                borderRadius: 8,
                border: forceMap?.map?.mapId === m.mapId ? '1px solid var(--kpi-blue)' : '1px solid var(--border-strong)',
                background: forceMap?.map?.mapId === m.mapId ? 'rgba(88,166,255,.14)' : 'rgba(255,255,255,.03)',
                color: 'var(--text)',
                padding: '8px 6px',
                cursor: 'pointer',
                fontSize: 13,
                fontFamily: 'inherit',
              }}>
              {m.name}
            </button>
          ))}
        </div>
      </Modal>
    </div>
  )
}

function SidePanel({ name, color, active, maps, banned }: { name: string; color: string; active: boolean; maps: BpListedMap[]; banned: BpListedMap[] }) {
  return (
    <CardBlock style={{ borderTop: `3px solid ${color}`, opacity: active ? 1 : 0.8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ width: 10, height: 10, borderRadius: 3, background: color, boxShadow: active ? `0 0 10px ${color}` : 'none' }} />
        <Text strong style={{ color }}>{name}</Text>
        {active && <Tag bordered={false} color={color === RED ? 'red' : 'blue'}>回合中</Tag>}
      </div>
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>已选择</Text>
      {maps.length === 0 ? <Text type="secondary" style={{ fontSize: 12 }}>无</Text> : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 10 }}>
          {maps.map((m, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
              <span style={{ width: 18, height: 18, borderRadius: 9, background: color, color: '#fff', fontSize: 11, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700 }}>{m.round}</span>
              <Text>{m.map_name}</Text>
            </div>
          ))}
        </div>
      )}
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>已禁用</Text>
      {banned.length === 0 ? <Text type="secondary" style={{ fontSize: 12 }}>无</Text> : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {banned.map((m, i) => <Tag key={i} bordered={false} style={{ fontSize: 11, textDecoration: 'line-through' }}>{m.map_name}</Tag>)}
        </div>
      )}
    </CardBlock>
  )
}

function CardBlock({ title, children, style }: { title?: string; children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ background: 'rgba(255,255,255,.02)', border: '1px solid var(--border)', borderRadius: 12, padding: '14px 16px', ...style }}>
      {title && <Text strong style={{ display: 'block', marginBottom: 10, fontSize: 13 }}>{title}</Text>}
      {children}
    </div>
  )
}

function turnText(t: string): string {
  const [side, action] = t.split('_')
  const s = side === 'BLUE' ? '蓝方' : '红方'
  return action === 'BAN' ? `${s} 禁用地图` : `${s} 选择地图`
}
function totalSteps(format: string): number {
  return format === 'BO1' ? 5 : format === 'BO3' ? 7 : 9
}
function short(s?: string): string {
  if (!s) return '-'
  return s.length <= 10 ? s : `${s.slice(0, 10)}…`
}
function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}