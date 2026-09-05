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
          colorBgBase: '#0d1117',
          colorBgContainer: '#161b22',
          colorBorder: '#30363d',
          borderRadius: 6,
          fontFamily: "'SimHei','PingFang SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif",
        },
        components: {
          Card: { borderRadiusLG: 10 },
          Table: { headerBg: '#1c2128' },
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