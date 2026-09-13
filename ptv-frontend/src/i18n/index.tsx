import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import zhCN from '../locales/zh-CN'
import zhTW from '../locales/zh-TW'
import en from '../locales/en'
import ja from '../locales/ja'
import ko from '../locales/ko'

export type LocaleCode = 'zh-CN' | 'zh-TW' | 'en' | 'ja' | 'ko'

const DICTS: Record<LocaleCode, Record<string, string>> = { 'zh-CN': zhCN, 'zh-TW': zhTW, en, ja, ko }

export const LOCALES: { code: LocaleCode; label: string; antd: string }[] = [
  { code: 'zh-CN', label: '简体中文', antd: 'zh-cn' },
  { code: 'zh-TW', label: '繁體中文', antd: 'zh-tw' },
  { code: 'en', label: 'English', antd: 'en' },
  { code: 'ja', label: '日本語', antd: 'ja-jp' },
  { code: 'ko', label: '한국어', antd: 'ko-kr' },
]

const STORAGE_KEY = 'pacc_locale'

function detectLocale(): LocaleCode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'zh-CN' || saved === 'zh-TW' || saved === 'en' || saved === 'ja' || saved === 'ko') return saved
  // 默认跟随浏览器语言：繁体区域自动用繁体
  const nav = typeof navigator !== 'undefined' ? navigator.language : ''
  if (nav.toLowerCase().startsWith('zh-tw') || nav.toLowerCase().startsWith('zh-hant')) return 'zh-TW'
  if (nav.toLowerCase().startsWith('zh')) return 'zh-CN'
  if (nav.toLowerCase().startsWith('ja')) return 'ja'
  if (nav.toLowerCase().startsWith('ko')) return 'ko'
  return 'zh-CN'
}

export interface I18nValue {
  t: (key: string, vars?: Record<string, string>) => string
  locale: LocaleCode
  setLocale: (locale: LocaleCode) => void
  locales: typeof LOCALES
}

const defaultCtx: I18nValue = {
  t: (k) => (zhCN as Record<string, string>)[k] ?? k,
  locale: 'zh-CN',
  setLocale: () => {},
  locales: LOCALES,
}

const I18nContext = createContext<I18nValue>(defaultCtx)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<LocaleCode>(detectLocale)

  const value = useMemo<I18nValue>(() => {
    const dict = DICTS[locale]
    return {
      locale,
      setLocale: (l) => {
        setLocaleState(l)
        localStorage.setItem(STORAGE_KEY, l)
      },
      t: (key, vars) => {
        let s = dict[key] ?? key
        if (vars) {
          for (const [k, v] of Object.entries(vars)) s = s.split(`{${k}}`).join(v)
        }
        return s
      },
      locales: LOCALES,
    }
  }, [locale])

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n() {
  return useContext(I18nContext)
}