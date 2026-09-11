// 统一检测 API 工厂：按运行平台分发到桌面(Tauri)/移动(Capacitor)/Web(降级)。
// 对应设计文档 §5.2 的跨端抽象（DesktopApi / MobileApi / createPaccApi）。
// 玩家端页面统一经此入口取检测能力，避免每页关心平台开关。

import { desktopBridge, isDesktop, type DetectionConfig, type DetectionStatus } from './desktopBridge'
import { mobileBridge, isNative } from './mobileBridge'

export interface DetectionApi {
  status: () => Promise<DetectionStatus>
  start: (cfg?: DetectionConfig) => Promise<DetectionStatus>
  stop: () => Promise<DetectionStatus>
  detections: (limit?: number) => Promise<Record<string, unknown>[]>
  available: boolean
  platform: 'desktop' | 'mobile' | 'web'
}

function toStatus(p: Record<string, unknown>): DetectionStatus {
  return {
    running: Boolean(p['running']),
    pteid: typeof p['pteid'] === 'string' ? p['pteid'] : undefined,
    version: typeof p['version'] === 'string' ? p['version'] : undefined,
    detection_count: typeof p['detection_count'] === 'number' ? p['detection_count'] : undefined,
    last_event_type: typeof p['last_event_type'] === 'string' ? p['last_event_type'] : undefined,
    redscreen_active: Boolean(p['redscreen_active']),
    redscreen_level: typeof p['redscreen_level'] === 'number' ? p['redscreen_level'] : undefined,
  }
}

/** 按平台创建检测 API 实例。 */
export function createDetectionApi(): DetectionApi {
  if (isNative() && !isDesktop()) {
    return {
      platform: 'mobile',
      available: true,
      status: () => mobileBridge.detection.status().then(toStatus),
      start: (cfg) => mobileBridge.detection.start(cfg).then(toStatus),
      stop: () => mobileBridge.detection.stop().then(toStatus),
      detections: (limit) =>
        mobileBridge.detection.detections(limit ?? 20).then((r) => r['results'] as unknown as Record<string, unknown>[]),
    }
  }
  if (isDesktop()) {
    return {
      platform: 'desktop',
      available: true,
      status: () => desktopBridge.status(),
      start: () => desktopBridge.start(),
      stop: () => desktopBridge.stop(),
      detections: (limit) => desktopBridge.detections(limit ?? 20) as Promise<Record<string, unknown>[]>,
    }
  }
  return {
    platform: 'web',
    available: false,
    status: () => Promise.reject(new Error('检测能力仅在原生 App 内可用')),
    start: () => Promise.reject(new Error('检测能力仅在原生 App 内可用')),
    stop: () => Promise.reject(new Error('检测能力仅在原生 App 内可用')),
    detections: () => Promise.reject(new Error('检测能力仅在原生 App 内可用')),
  }
}