import type { ReactNode } from 'react'
import { Card, Typography } from 'antd'

const { Title, Text } = Typography

/**
 * 玩家门户认证页统一外壳：居中卡片布局 + 品牌 LOGO 占位点。
 * 登录 / 注册 / 找回密码公用，保证三页视觉一致，避免各自复制卡片样式。
 */
export default function PlayerAuthShell({
  width,
  title,
  subtitle,
  children,
  footer,
}: {
  width?: number
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <div
      style={{
        minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px 12px',
      }}
    >
      <Card
        style={{ width: width ?? 400, boxShadow: '0 1px 2px rgba(0,0,0,0.4)' }}
        styles={{ body: { padding: 28 } }}
      >
        {/* ============ 品牌 LOGO 占位点 ============
            说明：Logo/企业外观标识本版本不替换。
            替换时在此插入 <img src="/logo.png" width={120} /> 并放置
            ptv-frontend/public/logo.png；当前保留文字标题。 */}
        <Title level={3} style={{ marginTop: 0, marginBottom: 0, color: '#ff6b5e' }}>
          {title}
        </Title>
        {subtitle && <Text type="secondary">{subtitle}</Text>}

        {children}

        {footer && (
          <div style={{ marginTop: 18, textAlign: 'center' }}>{footer}</div>
        )}
      </Card>
    </div>
  )
}