import { useCallback, useEffect, useRef, useState } from 'react'
import { eventBus, type WsHandler, type WsStatus } from './eventBus'

export interface UseWebSocketOptions {
  /** 连接建立（含重连成功）后调用，用于补发订阅帧。 */
  onOpen?: () => void
  /** 收到消息帧回调。 */
  onMessage: (msg: Record<string, unknown>) => void
}

/**
 * 基于全局 {@link eventBus} 的 React Hook：挂载即订阅、卸载即退订，
 * 连接生命周期（自动重连 / 心跳）由 EventBus 统一管理，组件内不再持有原生 WebSocket。
 * 传入空 URL 时跳过订阅（用于凭据异步解析完成前占位）。
 */
export function useWebSocket(url: string, options: UseWebSocketOptions) {
  const [status, setStatus] = useState<WsStatus>(() => (url ? eventBus.status(url) : 'disconnected'))

  // 用 ref 持有最新回调，避免订阅闭包过期（满足 react-hooks/exhaustive-deps）
  // ref 的更新放在 effect 中，避免渲染期写 ref（react-hooks/refs）
  const onOpenRef = useRef(options.onOpen)
  const onMessageRef = useRef(options.onMessage)

  useEffect(() => {
    onOpenRef.current = options.onOpen
    onMessageRef.current = options.onMessage
  })

  useEffect(() => {
    if (!url) return
    const handler: WsHandler = {
      onOpen: () => onOpenRef.current?.(),
      onMessage: (msg) => onMessageRef.current(msg),
      onStatus: (s) => setStatus(s),
    }
    return eventBus.subscribe(url, handler)
  }, [url])

  const send = useCallback(
    (payload: Record<string, unknown>) => {
      if (url) eventBus.send(url, payload)
    },
    [url],
  )

  return { status, send }
}
