import { useEffect, useRef, useState } from 'react'
import { Alert, Badge, Button, Card, Space, Typography, message } from 'antd'
import { createScreenShare, type SignalTransport } from '../../webrtc/webrtc'

const { Title } = Typography

interface Credentials { pteid: string; token: string }

/**
 * 玩家端 · 远程查端屏幕共享页（B2）。
 * <p>由桌面壳 WebView 挂载：凭据优先经 Tauri 桥 {@code screen_share_credentials} 注入，
 * 否则回退 URL 参数；随后以 pteid+token 连接 /ws/ptv。收到服务端 {@code inspect_request}
 * 后调用 getDisplayMedia 采集本屏并作为主叫发起 WebRTC（createScreenShare），
 * offer/answer/ice 信令全部复用该 WebSocket 通道。</p>
 */
export default function PlayerScreenShare() {
  const [status, setStatus] = useState<'连接中' | '共享中' | '已断开' | '错误'>('连接中')
  const [warn, setWarn] = useState('')
  const wsRef = useRef<WebSocket | null>(null)
  const screenRef = useRef<ReturnType<typeof createScreenShare> | null>(null)
  const aliveRef = useRef(true)

  /** 先问宿主桥，取不到再回退 URL 参数（纯浏览器联调场景）。 */
  async function resolveCredentials(params: URLSearchParams): Promise<Credentials | null> {
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
  }

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
      const ws = new WebSocket(
        `${proto}://${window.location.host}/ws/ptv?pteid=${encodeURIComponent(cred.pteid)}&token=${encodeURIComponent(cred.token)}`,
      )
      wsRef.current = ws

      const transport: SignalTransport = {
        send: (p) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(p)) },
      }

      ws.onopen = () => setStatus('连接中')
      ws.onclose = () => setStatus((s) => (s === '共享中' ? '已断开' : '已断开'))
      ws.onerror = () => setStatus('错误')
      ws.onmessage = async (ev) => {
        let msg: any
        try { msg = JSON.parse(ev.data as string) } catch { return }
        if (msg?.type === 'inspect_request') {
          try {
            const session = msg.session_id ?? ''
            const screen = screenRef.current ?? createScreenShare(transport, session)
            screenRef.current = screen
            await screen.start()
            setStatus('共享中')
            message.info('已开始屏幕共享，等待管理端接收')
          } catch (e) {
            setWarn(`屏幕采集失败：${(e as Error).message}`)
            setStatus('错误')
          }
        } else if (msg?.type === 'inspect_answer' || msg?.type === 'inspect_ice') {
          screenRef.current?.onSignal(msg as Record<string, unknown>)
        } else if (msg?.type === 'inspect_bye') {
          await stopShared()
        }
      }

      async function stopShared() {
        await screenRef.current?.stop()
        screenRef.current = null
        setStatus('已断开')
      }
    })()
    return () => {
      aliveRef.current = false
      wsRef.current?.close()
      wsRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function stop() {
    await screenRef.current?.stop()
    screenRef.current = null
    setStatus('已断开')
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