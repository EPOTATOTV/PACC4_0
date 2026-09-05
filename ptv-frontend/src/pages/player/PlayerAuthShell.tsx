import type { ReactNode } from 'react'
import { Card } from 'antd'
import Brand from '../../components/Brand'

/**
 * 玩家门户认证页统一外壳：居中卡片布局 + 品牌 logo。
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
        <Brand size="md" title={title} subtitle={subtitle} />

        {children}

        {footer && (
          <div style={{ marginTop: 18, textAlign: 'center' }}>{footer}</div>
        )}
      </Card>
    </div>
  )
}