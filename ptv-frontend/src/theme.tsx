import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'pacc_theme'

interface ThemeValue {
  /** 用户选择的模式（浅色 / 深色 / 跟随系统） */
  mode: ThemeMode
  /** 实际生效的主题（已解析「跟随系统」） */
  resolved: ResolvedTheme
  setMode: (mode: ThemeMode) => void
}

const defaultCtx: ThemeValue = { mode: 'system', resolved: 'dark', setMode: () => {} }
const ThemeContext = createContext<ThemeValue>(defaultCtx)

function storedMode(): ThemeMode {
  const v = localStorage.getItem(STORAGE_KEY)
  return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
}

function systemPrefersLight(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false
  return window.matchMedia('(prefers-color-scheme: light)').matches
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(storedMode)
  const [systemLight, setSystemLight] = useState(systemPrefersLight)

  // 跟随系统：监听系统深浅色变化，仅在「跟随系统」模式下生效
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: light)')
    const onChange = (e: MediaQueryListEvent) => setSystemLight(e.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const resolved: ResolvedTheme = mode === 'system' ? (systemLight ? 'light' : 'dark') : mode

  // 应用深浅色到 <html data-theme>，供 index.html 的 CSS 变量切换
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', resolved)
  }, [resolved])

  const value = useMemo<ThemeValue>(
    () => ({
      mode,
      resolved,
      setMode: (m) => {
        setModeState(m)
        localStorage.setItem(STORAGE_KEY, m)
      },
    }),
    [mode, resolved],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  return useContext(ThemeContext)
}