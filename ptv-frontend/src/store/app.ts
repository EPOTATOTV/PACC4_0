import { create } from 'zustand'

/**
 * 全局轻量状态（P1）：WS 连通、未读通知数、主题。由统一 WS 事件总线与各页面写入，
 * 供导航角标、主题切换等跨组件共享，避免重复状态与挂载耦合。
 */
type ThemeMode = 'dark' | 'light' | 'system'

interface AppState {
  theme: ThemeMode
  wsConnected: boolean
  unread: number
  setTheme: (t: ThemeMode) => void
  setWsConnected: (c: boolean) => void
  setUnread: (n: number) => void
}

const STORAGE_KEY = 'pacc_theme'

function initialTheme(): ThemeMode {
  const s = localStorage.getItem(STORAGE_KEY)
  return s === 'light' || s === 'system' ? (s as ThemeMode) : 'dark'
}

export const useAppStore = create<AppState>((set) => ({
  theme: initialTheme(),
  wsConnected: false,
  unread: 0,
  setTheme: (t) => {
    localStorage.setItem(STORAGE_KEY, t)
    set({ theme: t })
  },
  setWsConnected: (c) => set({ wsConnected: c }),
  setUnread: (n) => set({ unread: n }),
}))

export type { ThemeMode }