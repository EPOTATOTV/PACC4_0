// 桌面桥抽象层：把 Rust/Tauri 壳暴露检测控制的原生命令封装成平台无关接口。
// 在纯浏览器（Web）环境里这些能力不可用，统一返回 "仅在桌面端 App 可用" 提示，
// 玩家端页面据此降级展示，保证页面在 Server 端与桌面壳里都能渲染。
// 检测链路：前端 invoke → Rust 命令 → Java 本地控制服务(127.0.0.1)。

interface TauriGlobal {
  __TAURI__?: {
    core?: { invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown> }
    event?: {
      listen: (event: string, cb: (e: { payload: unknown }) => void) => Promise<() => void>
    }
  }
}

const win = window as unknown as TauriGlobal

export interface DetectionStatus {
  running: boolean
  uptime_sec?: number
  pteid?: string
  version?: string
  heartbeat_seconds?: number
  client_risk?: number
  enabled?: boolean
  detection_count?: number
  last_event_type?: string
  redscreen_active?: boolean
  redscreen_level?: number
}

export interface DetectionConfig {
  heartbeat_seconds?: number
  client_risk?: number
  enabled?: boolean
}

/** 是否运行在桌面壳（Tauri WebView）内。 */
export function isDesktop(): boolean {
  return Boolean(win.__TAURI__?.core?.invoke)
}

async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!win.__TAURI__?.core?.invoke) {
    throw new Error('仅在桌面端 App 可用')
  }
  return win.__TAURI__.core.invoke(cmd, args) as Promise<T>
}

/** 订阅桌面壳推送的事件，返回取消订阅函数。 */
export function onEvent(name: string, cb: (payload: unknown) => void): () => void {
  let unlisten: (() => void) | undefined
  win.__TAURI__?.event?.listen(name, (e) => cb(e.payload)).then((u) => (unlisten = u))
  return () => unlisten?.()
}

export const desktopBridge = {
  isDesktop,

  status: () => invoke<DetectionStatus>('detection_status'),
  start: () => invoke<DetectionStatus>('detection_start'),
  stop: () => invoke<DetectionStatus>('detection_stop'),
  detections: (limit = 20) => invoke<Record<string, unknown>[]>('get_detections', { limit }),
  getConfig: () => invoke<DetectionConfig>('get_detection_config'),
  updateConfig: (cfg: DetectionConfig) => invoke<DetectionConfig>('update_detection_config', { cfg }),
  pteid: () => invoke<{ pteid: string }>('pteid_info'),
  openLogs: () => invoke<void>('open_logs'),
  setAutostart: (enabled: boolean) => invoke<void>('set_autostart', { enabled }),
  onEvent,
}