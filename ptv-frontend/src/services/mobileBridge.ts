// 移动端桥接层（Capacitor）：前端以 window.Capacitor 调用原生插件。
// 与 desktopBridge.ts 对齐同一套 DetectionStatus 抽象，玩家端页面可按平台取用。
// 纯浏览器（Web）与桌面端（Tauri）不通过本层——浏览器 Capcitor 全局不存在，
// 桌面端走 window.__TAURI__，见 desktopBridge.ts / pacc-api.ts 的平台分发。

import type { DetectionConfig } from './desktopBridge'

interface CapacitorGlobal {
  Capacitor?: {
    isNativePlatform: () => boolean
    Plugins: Record<string, { [method: string]: (...args: unknown[]) => Promise<Record<string, unknown> | void> }>
    addListener?: (event: string, cb: (data: { value?: unknown }) => void) => Promise<{ remove: () => void }>
  }
}

const win = window as unknown as CapacitorGlobal

export function isNative(): boolean {
  return Boolean(win.Capacitor?.isNativePlatform?.())
}

function plugin(name: string) {
  const p = win.Capacitor?.Plugins?.[name]
  if (!p) throw new Error('原生插件不可用：' + name)
  return p
}

type PluginApi = Record<string, (...args: unknown[]) => Promise<Record<string, unknown> | void>>

/** 移动端检测桥：状态 / 启动 / 停止 / 记录（能力相对桌面端更轻，见设计文档 4.1）。 */
export const mobileBridge = {
  isNative,
  detection: {
    status: () => (plugin('PaccDetection') as PluginApi)['status']() as Promise<Record<string, unknown>>,
    start: (cfg?: DetectionConfig) =>
      (plugin('PaccDetection') as PluginApi)['start'](cfg ?? {}) as Promise<Record<string, unknown>>,
    stop: () => (plugin('PaccDetection') as PluginApi)['stop']() as Promise<Record<string, unknown>>,
    detections: (limit = 20) =>
      (plugin('PaccDetection') as PluginApi)['detections']({ limit }) as Promise<Record<string, unknown>>,
  },
  redScreen: {
    show: (info: { level?: number; reason?: string }) =>
      (plugin('PaccRedScreen') as PluginApi)['show'](info ?? {}) as Promise<Record<string, unknown>>,
    dismiss: () => (plugin('PaccRedScreen') as PluginApi)['dismiss']() as Promise<Record<string, unknown>>,
  },
  biometric: {
    check: () => (plugin('PaccBiometric') as PluginApi)['isAvailable']() as Promise<Record<string, unknown>>,
    auth: (reason: string) =>
      (plugin('PaccBiometric') as PluginApi)['auth']({ reason }) as Promise<Record<string, unknown>>,
  },
  push: {
    sync: (token: string) =>
      (plugin('PaccPush') as PluginApi)['registerToken']({ token }) as Promise<Record<string, unknown>>,
  },
}