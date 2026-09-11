import { useEffect, useState } from 'react'
import { Alert, Badge, Button, Card, Space, Switch, Tag, Typography, message } from 'antd'
import { desktopBridge, type DetectionStatus } from '../../services/desktopBridge'
import { createDetectionApi, type DetectionApi } from '../../services/pacc-api'

const { Text } = Typography

interface Item {
  event_type?: string
  severity?: string
  client_risk?: number
}

/**
 * 玩家端 · 检测引擎控制面板（跨端）。
 * <p>经统一 API（createDetectionApi）按平台分发：桌面壳走 Rust→Java 本地控制服务，
 * 移动端走 Capacitor 原生检测桥，纯浏览器降级提示。桌面专属能力（打开日志/自启动/事件推送）仅在桌面端展示。</p>
 */
export default function PlayerDetectionPanel() {
  const [api] = useState<DetectionApi>(createDetectionApi)
  const [st, setSt] = useState<DetectionStatus | null>(null)
  const [list, setList] = useState<Item[]>([])
  const [busy, setBusy] = useState(false)
  const [autostart, setAutostart] = useState(true)

  const isDesktop = desktopBridge.isDesktop()

  useEffect(() => {
    if (!api.available) return
    api.status().then(setSt).catch(() => setSt(null))
    api.detections(10).then(setList).catch(() => undefined)
    // 桌面端订阅壳推送到前端的事件（移动端红屏/告警由原生侧处理）
    if (!isDesktop) return
    const offUpdate = desktopBridge.onEvent('status_update', (p) => setSt(p as DetectionStatus))
    const offAlert = desktopBridge.onEvent('detection_alert', (p) => {
      const e = p as { event_type?: string }
      message.warning(`检测到可疑行为：${e.event_type ?? '未知'}`)
      desktopBridge.detections(10).then(setList).catch(() => undefined)
    })
    return () => { offUpdate?.(); offAlert?.() }
  }, [api, isDesktop])

  async function run(fn: () => Promise<unknown>) {
    setBusy(true)
    try {
      await fn()
      const s = await api.status()
      setSt(s)
      const d = await api.detections(10)
      setList(d as Item[])
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (!api.available) {
    return <Alert type="info" showIcon message={'检测能力仅在原生 App（桌面端/移动端）内可用。'} />
  }

  const platformLabel = 'desktop' === api.platform ? '桌面端' : '移动端'

  return (
    <Card title={`检测控制台 · ${platformLabel}`} size="small">
      {st && (
        <Space size={12} wrap style={{ marginBottom: 12 }}>
          <Badge status={st.running ? "processing" : "default"} text={st.running ? "检测运行中" : "已暂停"} />
          {st.pteid && <Tag>{st.pteid}</Tag>}
          {st.version && <Text type="secondary">v{st.version}</Text>}
          {st.redscreen_active && <Tag color="red">红屏激活（L{st.redscreen_level}）</Tag>}
        </Space>
      )}
      <Space size={8} wrap style={{ marginBottom: 12 }}>
        <Button type="primary" loading={busy} disabled={st?.running} onClick={() => run(() => api.start())}>
          开始检测
        </Button>
        <Button danger loading={busy} disabled={!st?.running} onClick={() => run(() => api.stop())}>
          停止检测
        </Button>
        {isDesktop && (
          <Button onClick={() => desktopBridge.openLogs().then(() => message.success('已打开日志目录'))}>
            打开日志
          </Button>
        )}
      </Space>
      {isDesktop && st && (
        <Space size={12} wrap>
          <span>
            <Text type="secondary">自启动</Text>
            <Switch
              size="small" style={{ marginLeft: 6 }} checked={autostart}
              onChange={(v) => desktopBridge.setAutostart(v).then(() => setAutostart(v))}
            />
          </span>
        </Space>
      )}
      {list.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <Text strong>最近检测记录</Text>
          {list.map((it, i) => (
            <Space key={i} size={8} style={{ display: 'flex', marginTop: 6 }}>
              <Tag color={severityColor(it.severity)}>{it.event_type ?? '-'}</Tag>
              <Text type="secondary">风险 {it.client_risk ?? '-'}</Text>
            </Space>
          ))}
        </div>
      )}
    </Card>
  )
}

function severityColor(s?: string): string {
  switch (s) {
    case 'critical': return 'red'
    case 'high': return 'volcano'
    case 'medium': return 'orange'
    case 'low': return 'green'
    default: return 'default'
  }
}