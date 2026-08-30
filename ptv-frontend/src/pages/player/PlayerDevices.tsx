import { useEffect, useState } from 'react'
import { Alert, Card, List, Tag, Typography } from 'antd'
import { api } from '../../api/client'
import type { DeviceRecord, Peripheral } from '../../types'

const { Title, Text } = Typography

const kindNames: Record<string, string> = {
  mouse: '鼠标', keyboard: '键盘', headset: '耳机', gamepad: '手柄',
  usb_storage: 'USB 存储', dongle: '投屏/USB 适配器', monitor: '显示器', usb: 'USB 设备',
}

export default function PlayerDevices() {
  const [devices, setDevices] = useState<DeviceRecord[]>([])
  const [peripherals, setPeripherals] = useState<Peripheral[]>([])
  const [err, setErr] = useState('')
  const [loadingDev, setLoadingDev] = useState(true)
  const [loadingPer, setLoadingPer] = useState(true)

  useEffect(() => {
    api.player.devices().then(setDevices).catch((e) => setErr((e as Error).message)).finally(() => setLoadingDev(false))
    api.player.peripherals().then(setPeripherals).catch((e) => setErr((e as Error).message)).finally(() => setLoadingPer(false))
  }, [])

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>我的设备</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Card title="登录设备" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        <List
          loading={loadingDev}
          locale={{ emptyText: '暂无登录设备记录' }}
          dataSource={devices}
          renderItem={(d) => (
            <List.Item
              key={d.deviceId}
              style={{ padding: '14px 20px' }}
              actions={[<Tag key="tag" color={d.active ? 'success' : 'default'}>{d.active ? '当前使用' : '未使用'}</Tag>]}
            >
              <List.Item.Meta
                title={d.deviceName || '未知设备'}
                description={
                  <Text type="secondary" style={{ fontSize: 12, fontFamily: 'monospace' }}>
                    {d.deviceFingerprint ? `指纹 ${d.deviceFingerprint.slice(0, 12)}…` : ''} · 首次登录 {fmt(d.firstLoginAt)} · 最近 {fmt(d.lastLoginAt)}
                  </Text>
                }
              />
            </List.Item>
          )}
        />
      </Card>

      <Card title="使用过的外设" styles={{ body: { padding: 0 } }}>
        <List
          loading={loadingPer}
          locale={{ emptyText: '暂无外设记录' }}
          dataSource={peripherals}
          renderItem={(p) => (
            <List.Item
              key={p.peripheralId}
              style={{ padding: '14px 20px' }}
              actions={[<Tag key="tag" color={p.connected ? 'success' : 'default'}>{p.connected ? '正在使用' : '未使用'}</Tag>]}
            >
              <List.Item.Meta
                title={`${kindNames[p.kind] ?? p.kind}${p.vendor ? ` · ${p.vendor}` : ''}${p.model && p.model !== 'unknown' ? ` ${p.model}` : ''}`}
                description={<Text type="secondary" style={{ fontSize: 12 }}>最近检测 {fmt(p.lastSeenAt)}</Text>}
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}