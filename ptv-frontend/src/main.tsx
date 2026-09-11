import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import zhTW from 'antd/locale/zh_TW'
import enUS from 'antd/locale/en_US'
import App from './App'
import { I18nProvider, useI18n, type LocaleCode } from './i18n'

// antd 内置语言包映射，跟随前台切换
const ANTD_LOCALES: Record<LocaleCode, typeof zhCN> = {
  'zh-CN': zhCN,
  'zh-TW': zhTW,
  en: enUS,
}

function Root() {
  const { locale } = useI18n()
  return (
    <ConfigProvider
      locale={ANTD_LOCALES[locale]}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
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
          fontFamily: "'JetBrains Mono',ui-monospace,'SimHei','PingFang SC','Microsoft YaHei','Noto Sans CJK SC',monospace",
          boxShadow: '0 8px 30px rgba(0,0,0,.45)',
          boxShadowSecondary: '0 4px 16px rgba(0,0,0,.4)',
        },
        components: {
          Card: { borderRadiusLG: 12, colorBgContainer: 'rgba(17,20,27,.72)', headerBg: 'transparent' },
          Table: { headerBg: '#141820', rowHoverBg: 'rgba(255,255,255,.025)' },
          Menu: { itemBg: 'transparent', subMenuItemBg: 'transparent' },
          Layout: { siderBg: 'transparent', bodyBg: 'transparent' },
        },
      }}
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
        <Root />
      </I18nProvider>
    </React.StrictMode>
  )
}

const rootEl = document.getElementById('root') as HTMLElement
ReactDOM.createRoot(rootEl).render(<Main />)