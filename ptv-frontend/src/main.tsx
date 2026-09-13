import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import zhTW from 'antd/locale/zh_TW'
import enUS from 'antd/locale/en_US'
import jaJP from 'antd/locale/ja_JP'
import koKR from 'antd/locale/ko_KR'
import App from './App'
import { I18nProvider, useI18n, type LocaleCode } from './i18n'
import { ThemeProvider, useTheme, type ResolvedTheme } from './theme'

// antd 内置语言包映射，跟随前台切换
const ANTD_LOCALES: Record<LocaleCode, typeof zhCN> = {
  'zh-CN': zhCN,
  'zh-TW': zhTW,
  en: enUS,
  ja: jaJP,
  ko: koKR,
}

const FONT_FAMILY =
  "'SimHei','PingFang SC','Microsoft YaHei','Noto Sans CJK SC',ui-monospace,Menlo,Consolas,monospace"

function buildTheme(resolved: ResolvedTheme) {
  const dark = resolved === 'dark'
  return {
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: dark
      ? {
          colorPrimary: '#ff6b5e',
          colorBgBase: '#050608',
          colorBgContainer: '#101319',
          colorBgElevated: '#161a22',
          colorBgLayout: '#050608',
          colorBorder: 'rgba(255,255,255,.10)',
          colorBorderSecondary: 'rgba(255,255,255,.06)',
          colorText: '#eef0f4',
          colorTextSecondary: '#98a0ae',
          colorTextTertiary: '#626b7a',
          borderRadius: 8,
          fontSize: 13,
          fontFamily: FONT_FAMILY,
          boxShadow: '0 8px 30px rgba(0,0,0,.45)',
          boxShadowSecondary: '0 4px 16px rgba(0,0,0,.4)',
        }
      : {
          colorPrimary: '#d2433a',
          colorBgBase: '#f3f5f9',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBgLayout: '#edeff4',
          colorBorder: 'rgba(15,23,42,.12)',
          colorBorderSecondary: 'rgba(15,23,42,.08)',
          colorText: '#111827',
          colorTextSecondary: '#4b5563',
          colorTextTertiary: '#9aa3b2',
          borderRadius: 8,
          fontSize: 13,
          fontFamily: FONT_FAMILY,
          boxShadow: '0 8px 30px rgba(15,23,42,.12)',
          boxShadowSecondary: '0 4px 16px rgba(15,23,42,.10)',
        },
    components: dark
      ? {
          Card: { borderRadiusLG: 12, colorBgContainer: 'rgba(17,20,27,.72)', headerBg: 'transparent' },
          Table: { headerBg: '#141820', rowHoverBg: 'rgba(255,255,255,.025)' },
          Menu: { itemBg: 'transparent', subMenuItemBg: 'transparent' },
          Layout: { siderBg: 'transparent', bodyBg: 'transparent' },
        }
      : {
          Card: { borderRadiusLG: 12, colorBgContainer: 'rgba(255,255,255,.80)', headerBg: 'transparent' },
          Table: { headerBg: '#e9ecf2', rowHoverBg: 'rgba(15,23,42,.03)' },
          Menu: { itemBg: 'transparent', subMenuItemBg: 'transparent' },
          Layout: { siderBg: 'transparent', bodyBg: 'transparent' },
        },
  }
}

function ThemedRoot() {
  const { locale } = useI18n()
  const { resolved } = useTheme()
  return (
    <ConfigProvider
      locale={ANTD_LOCALES[locale]}
      theme={buildTheme(resolved)}
    >
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default function Main() {
  return (
    <React.StrictMode>
      <I18nProvider>
        <ThemeProvider>
          <ThemedRoot />
        </ThemeProvider>
      </I18nProvider>
    </React.StrictMode>
  )
}

const rootEl = document.getElementById('root') as HTMLElement
ReactDOM.createRoot(rootEl).render(<Main />)

// PWA：生产环境注册离线缓存 Service Worker；仅限 https / localhost
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* 注册失败不阻塞页面（如无缓存权限的环境） */
    })
  })
}