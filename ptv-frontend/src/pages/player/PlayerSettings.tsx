import { useEffect, useState } from 'react'
import { Alert, Card, Descriptions, Divider, Segmented, Space, Tag, Typography } from 'antd'
import { api } from '../../api/client'
import { useI18n, type LocaleCode } from '../../i18n'
import { useTheme, type ThemeMode } from '../../theme'

const { Title, Text } = Typography

export default function PlayerSettings() {
  const [pteid, setPteid] = useState('')
  const [err, setErr] = useState('')
  // 主题 / 语言统一走上下文：真正驱动 antd 算法与本机偏好
  const { mode: theme, setMode: setTheme } = useTheme()
  const { locale: lang, setLocale: setLang, locales } = useI18n()

  useEffect(() => {
    api.player.me().then((m) => setPteid(m.pteid)).catch((e) => setErr(String(e.message || e)))
  }, [])

  const moduleSwitches = [
    ['内存与进程检测', '内存篡改、进程注入'],
    ['输入行为分析', '连点器、压枪、宏'],
    ['外设检测', '键盘 / USB 特殊设备'],
    ['环境与驱动检测', '调试器、内核异常'],
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>客户端设置</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Card title="账号与安全" variant="borderless" style={{ marginBottom: 16 }}>
        <Space wrap size={16}>
          <Tag color="geekblue" style={{ padding: '2px 10px' }}>PTEID {pteid || '—'}</Tag>
          <Text type="secondary">登录标识固定，不可修改；解绑设备请在「我的设备」操作。</Text>
        </Space>
      </Card>

      <Card title="界面外观" variant="borderless" style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 12 }}>
          <Text style={{ display: 'block', marginBottom: 6, fontWeight: 500 }}>主题</Text>
          <Segmented
            value={theme}
            options={[
              { label: '浅色', value: 'light' },
              { label: '深色', value: 'dark' },
              { label: '跟随系统', value: 'system' },
            ]}
            onChange={(v) => setTheme(v as ThemeMode)}
          />
        </div>
        <div>
          <Text style={{ display: 'block', marginBottom: 6, fontWeight: 500 }}>语言</Text>
          <Segmented
            value={lang}
            options={locales.map((l) => ({ label: l.label, value: l.code }))}
            onChange={(v) => setLang(v as LocaleCode)}
            style={{ overflowX: 'auto' }}
          />
        </div>
        <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
          偏好保存在本机；浅色 / 深色即时生效，「跟随系统」随系统深浅色自动切换。桌面端客户端壳据此渲染对应外观。
        </Text>
      </Card>

      <Card title="反作弊守护（客户端自动启动 · 强制运行）" variant="borderless">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="客户端启动即自动加载反作弊守护，全程强制运行，无手动开关，玩家不可关闭或调整。"
        />
        <Descriptions column={2} size="small" labelStyle={{ width: 150 }}>
          {moduleSwitches.map(([k, v]) => (
            <Descriptions.Item key={k} label={k} span={2}>{v} · 已启用</Descriptions.Item>
          ))}
          <Descriptions.Item label="红屏警告" span={2}>触发时全屏强制提示，未确认作弊可选「我已知晓，继续游戏」</Descriptions.Item>
        </Descriptions>
      </Card>

      <Divider />
      <Text type="secondary" style={{ fontSize: 12 }}>PACC 玩家端 v5.0 · 本文档客户端页面为管理端同源 Web 门户，桌面客户端外观与覆盖由安装端承接。</Text>
    </div>
  )
}