import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Badge, Button, Card, Space, Typography, message } from 'antd'
import { createScreenShare } from '../../webrtc/webrtc'
import { useWebSocket } from '../../ws/useWebSocket'

const { Title } = Typography

interface Credentials { pteid: string; token: string }

type ScreenStatus = '连接中' | '共享中' | '已断开' | '错误'

/**
 * 玩家端 · 远程查端屏幕共享页（B2）。
 * <p>由桌面壳 WebView 挂载：凭据优先经 Tauri 桥 {@code screen_share_credentials} 注入，
 * 否则回退 URL 参数；随后以 pteid+token 连接 /ws/ptv（统一事件总线）。
 * 收到服务端 {@code inspect_request} 后调用 getDisplayMedia 采集本屏并作为主叫发起
 * WebRTC（createScreenShare），offer/answer/ice 信令全部复用该 WebSocket 通道。</p>
 */
export default function PlayerScreenShare() {
  const [status, setStatus] = useState<ScreenStatus>('连接中')
  const [warn, setWarn] = useState('')
  const [wsUrl, setWsUrl] = useState('')
  const screenRef = useRef<ReturnType<typeof createScreenShare> | null>(null)
  const aliveRef = useRef(true)

  /** 先问宿主桥，取不到再回退 URL 参数（纯浏览器联调场景）。 */
  const resolveCredentials = useCallback(async (params: URLSearchParams): Promise<Credentials | null> => {
    const win = window as unknown as { __TAURI__?: { core?: { invoke: (cmd: string) => Promise<{ pteid: string; token: string }> } } }
    if (win.__TAURI__?.core?.invoke) {
      try {
        return await win.__TAURI__.core.invoke('screen_share_credentials')
      } catch {
        // 桥未注入，继续回退 URL 参数
      }
    }
    const pteid = params.get('pteid')
    const token = params.get('token')
    if (pteid && token) return { pteid, token }
    return null
  }, [])

  // 解析凭据并构建 WS 端点（解析完成前不建立连接）
  useEffect(() => {
    aliveRef.current = true
    const params = new URLSearchParams(window.location.search)
    void (async () => {
      const cred = await resolveCredentials(params)
      if (!aliveRef.current) return
      if (!cred) {
        setStatus('错误')
        setWarn('未获取到查端凭据（Tauri 桥未注入，且 URL 缺少 pteid/token）')
        return
      }
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
      setWsUrl(`${proto}://${window.location.host}/ws/ptv?pteid=${encodeURIComponent(cred.pteid)}&token=${encodeURIComponent(cred.token)}`)
    })()
    return () => { aliveRef.current = false }
  }, [resolveCredentials])

  const stopShared = useCallback(async () => {
    await screenRef.current?.stop()
    screenRef.current = null
    setStatus('已断开')
  }, [])

  const { status: wsStatus, send } = useWebSocket(wsUrl, {
    onMessage: async (record) => {
      if (record?.type === 'inspect_request') {
        try {
          const session = String(record.session_id ?? '')
          const screen = screenRef.current ?? createScreenShare({ send }, session)
          screenRef.current = screen
          await screen.start()
          setStatus('共享中')
          message.info('已开始屏幕共享，等待管理端接收')
        } catch (e) {
          setWarn(`屏幕采集失败：${(e as Error).message}`)
          setStatus('错误')
        }
      } else if (record?.type === 'inspect_answer' || record?.type === 'inspect_ice') {
        screenRef.current?.onSignal(record)
      } else if (record?.type === 'inspect_bye') {
        await stopShared()
      }
    },
  })

  // 连接状态 → 页面展示状态（共享中保持，其余跟随 WS）
  useEffect(() => {
    if (wsStatus === 'connected') {
      setStatus((s) => (s === '共享中' ? s : '连接中'))
    } else if (wsStatus === 'disconnected' || wsStatus === 'reconnecting') {
      setStatus((s) => (s === '共享中' ? s : '已断开'))
    }
  }, [wsStatus])

  async function stop() {
    await stopShared()
  }

  return (
    <div style={{ padding: 24, maxWidth: 560, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>远程查端 · 屏幕共享</Title>
      </div>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card size="small">
          <Badge
            status={status === '共享中' ? 'processing' : status === '已断开' || status === '错误' ? 'error' : 'success'}
            text={status === '连接中' ? '已连接，等待查端请求' : status}
          />
        </Card>
        {warn && <Alert type="error" showIcon message={warn} />}
        <Button danger block disabled={status !== '共享中'} onClick={() => void stop()}>
          结束共享
        </Button>
      </Space>
    </div>
  )
}
