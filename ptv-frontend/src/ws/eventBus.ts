/**
 * 前端统一 WebSocket 事件总线（v5.0）。
 * 以 URL 为键维护单例连接：同一端点共享一条 WS，具备自动重连（指数退避 ≤30s）、
 * 30s 心跳 ping、连接状态广播与按消息 type 分发的订阅/发布。
 * 消息约定沿用现有后端协议：JSON 文本帧，形如 { type, ...payload }。
 */
export type WsStatus = 'connecting' | 'connected' | 'disconnected' | 'reconnecting'

export interface WsHandler {
  /** 连接建立（含重连成功）时回调，用于补发 bp_subscribe 等订阅帧。 */
  onOpen?: () => void
  /** 收到文本帧（已解析为对象）时回调。 */
  onMessage: (msg: Record<string, unknown>) => void
  /** 连接状态变化回调。 */
  onStatus?: (status: WsStatus) => void
}

interface WsConnection {
  url: string
  ws: WebSocket | null
  status: WsStatus
  handlers: Set<WsHandler>
  retries: number
  retryTimer?: ReturnType<typeof setTimeout>
  hbTimer?: ReturnType<typeof setInterval>
  /** 组件全部退订后置 true，阻止重连；后续 subscribe 重新拉起。 */
  closed: boolean
}

const MAX_RETRY_MS = 30_000
const HB_INTERVAL_MS = 30_000

class EventBus {
  private conns = new Map<string, WsConnection>()

  /** 订阅某端点的消息；返回退订函数。同一 URL 首次订阅时建立连接。 */
  subscribe(url: string, handler: WsHandler): () => void {
    const conn = this.ensure(url)
    conn.handlers.add(handler)
    this.notifyStatus(conn, conn.status)
    return () => {
      conn.handlers.delete(handler)
      if (conn.handlers.size === 0) {
        this.teardown(conn)
      }
    }
  }

  /** 向某端点发送 JSON 帧；连接未就绪时静默丢弃（订阅帧请走 onOpen 补发）。 */
  send(url: string, payload: Record<string, unknown>): void {
    const conn = this.conns.get(url)
    if (conn?.ws?.readyState === WebSocket.OPEN) {
      conn.ws.send(JSON.stringify(payload))
    }
  }

  /** 向某端点发送原始文本帧（信令通道等特殊场景）。 */
  sendRaw(url: string, text: string): void {
    const conn = this.conns.get(url)
    if (conn?.ws?.readyState === WebSocket.OPEN) {
      conn.ws.send(text)
    }
  }

  /** 查询某端点当前连接状态。 */
  status(url: string): WsStatus {
    return this.conns.get(url)?.status ?? 'disconnected'
  }

  private ensure(url: string): WsConnection {
    let conn = this.conns.get(url)
    if (!conn) {
      conn = { url, ws: null, status: 'connecting', handlers: new Set(), retries: 0, closed: false }
      this.conns.set(url, conn)
      this.connect(conn)
    } else if (conn.closed) {
      conn.closed = false
      conn.retries = 0
      this.connect(conn)
    }
    return conn
  }

  private connect(conn: WsConnection): void {
    if (conn.closed) return
    this.clearTimers(conn)
    this.notifyStatus(conn, conn.retries > 0 ? 'reconnecting' : 'connecting')
    let ws: WebSocket
    try {
      ws = new WebSocket(conn.url)
    } catch {
      this.scheduleReconnect(conn)
      return
    }
    conn.ws = ws

    ws.onopen = () => {
      if (conn.closed) return
      conn.retries = 0
      conn.status = 'connected'
      this.notifyStatus(conn, 'connected')
      this.startHeartbeat(conn)
      conn.handlers.forEach((h) => h.onOpen?.())
    }
    ws.onmessage = (ev) => {
      if (conn.closed) return
      let msg: unknown
      try {
        msg = JSON.parse(ev.data as string)
      } catch {
        return // 非 JSON 帧忽略（后端文本帧均为 JSON）
      }
      if (msg && typeof msg === 'object') {
        const record = msg as Record<string, unknown>
        conn.handlers.forEach((h) => h.onMessage(record))
      }
    }
    ws.onclose = () => {
      if (conn.closed) return
      conn.status = 'disconnected'
      this.stopHeartbeat(conn)
      this.notifyStatus(conn, 'disconnected')
      this.scheduleReconnect(conn)
    }
    ws.onerror = () => {
      if (conn.closed) return
      conn.status = 'disconnected'
      this.stopHeartbeat(conn)
      this.notifyStatus(conn, 'disconnected')
    }
  }

  /** 指数退避重连：1s → 2s → 4s … 封顶 30s。 */
  private scheduleReconnect(conn: WsConnection): void {
    if (conn.closed) return
    const delay = Math.min(MAX_RETRY_MS, 1000 * 2 ** Math.min(conn.retries, 5))
    conn.retries += 1
    conn.retryTimer = setTimeout(() => this.connect(conn), delay)
  }

  private startHeartbeat(conn: WsConnection): void {
    this.stopHeartbeat(conn)
    conn.hbTimer = setInterval(() => {
      if (conn.ws?.readyState === WebSocket.OPEN) {
        conn.ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, HB_INTERVAL_MS)
  }

  private stopHeartbeat(conn: WsConnection): void {
    if (conn.hbTimer) {
      clearInterval(conn.hbTimer)
      conn.hbTimer = undefined
    }
  }

  private clearTimers(conn: WsConnection): void {
    this.stopHeartbeat(conn)
    if (conn.retryTimer) {
      clearTimeout(conn.retryTimer)
      conn.retryTimer = undefined
    }
  }

  /** 全部退订时关闭连接并移除记录；下次订阅重建。 */
  private teardown(conn: WsConnection): void {
    conn.closed = true
    this.clearTimers(conn)
    try {
      conn.ws?.close()
    } catch {
      // 忽略关闭异常
    }
    conn.ws = null
    conn.status = 'disconnected'
    this.conns.delete(conn.url)
  }

  private notifyStatus(conn: WsConnection, status: WsStatus): void {
    conn.status = status
    conn.handlers.forEach((h) => h.onStatus?.(status))
  }
}

/** 全局单例：全站共享一条/少数几条 WS 连接。 */
export const eventBus = new EventBus()
