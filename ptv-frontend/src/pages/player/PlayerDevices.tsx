import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { DeviceRecord, Peripheral } from '../../types'

const kindNames: Record<string, string> = {
  mouse: '鼠标', keyboard: '键盘', headset: '耳机', gamepad: '手柄',
  usb_storage: 'USB 存储', dongle: '投屏/USB 适配器', monitor: '显示器', usb: 'USB 设备',
}

export default function PlayerDevices() {
  const [devices, setDevices] = useState<DeviceRecord[]>([])
  const [peripherals, setPeripherals] = useState<Peripheral[]>([])
  const [err, setErr] = useState('')

  useEffect(() => {
    api.player.devices().then(setDevices).catch((e) => setErr((e as Error).message))
    api.player.peripherals().then(setPeripherals).catch((e) => setErr((e as Error).message))
  }, [])

  return (
    <div>
      <h1 style={{ marginTop: 0 }}>我的设备</h1>
      {err && <div style={{ color: '#ff3b30', marginBottom: 12 }}>{err}</div>}

      <h2 style={{ fontSize: 16 }}>登录设备</h2>
      <div className="card" style={{ padding: 0 }}>
        {devices.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>暂无登录设备记录</div>}
        {devices.map((d) => (
          <div key={d.deviceId} style={{ padding: '12px 16px', borderTop: '1px solid #131920', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{d.deviceName || '未知设备'}</div>
              <div style={{ color: '#8b949e', fontSize: 11, fontFamily: 'monospace' }}>
                {d.deviceFingerprint ? `指纹 ${d.deviceFingerprint.slice(0, 12)}…` : ''} · 首次登录 {fmt(d.firstLoginAt)} · 最近 {fmt(d.lastLoginAt)}
              </div>
            </div>
            <Tag active={d.active} />
          </div>
        ))}
      </div>

      <h2 style={{ fontSize: 16, marginTop: 24 }}>使用过的外设</h2>
      <div className="card" style={{ padding: 0 }}>
        {peripherals.length === 0 && <div style={{ padding: 16, color: '#8b949e' }}>暂无外设记录</div>}
        {peripherals.map((p) => (
          <div key={p.peripheralId} style={{ padding: '12px 16px', borderTop: '1px solid #131920', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, fontSize: 13 }}>
                {kindNames[p.kind] ?? p.kind}
                {p.vendor ? ` · ${p.vendor}` : ''}
                {p.model && p.model !== 'unknown' ? ` ${p.model}` : ''}
              </div>
              <div style={{ color: '#8b949e', fontSize: 11 }}>最近检测 {fmt(p.lastSeenAt)}</div>
            </div>
            <Connected on={p.connected} />
          </div>
        ))}
      </div>
    </div>
  )
}

function Tag({ active }: { active: boolean }) {
  return (
    <span style={{
      fontSize: 11, whiteSpace: 'nowrap', padding: '2px 10px', borderRadius: 12,
      background: active ? '#3fb950' : '#30363d',
      color: active ? '#0d1117' : '#8b949e',
      fontWeight: active ? 600 : 400,
    }}>
      {active ? '当前使用' : '未使用'}
    </span>
  )
}

function Connected({ on }: { on: boolean }) {
  return (
    <span style={{
      fontSize: 11, whiteSpace: 'nowrap', padding: '2px 10px', borderRadius: 12,
      background: on ? '#3fb950' : '#30363d',
      color: on ? '#0d1117' : '#8b949e',
      fontWeight: on ? 600 : 400,
    }}>
      {on ? '正在使用' : '未使用'}
    </span>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}