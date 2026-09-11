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
        position: 'relative',
        minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px 12px',
      }}
    >
      {/* 环境氛围：顶部微光 + 细网格 */}
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden',
          background:
            'radial-gradient(760px 430px at 50% -120px, rgba(255,77,61,.16), transparent 62%), var(--bg)',
        }}
      />
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, opacity: .5,
          backgroundImage:
            'linear-gradient(rgba(255,255,255,.035) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.035) 1px, transparent 1px)',
          backgroundSize: '52px 52px',
          maskImage: 'radial-gradient(circle at 50% 0%, #000 0%, transparent 70%)',
          WebkitMaskImage: 'radial-gradient(circle at 50% 0%, #000 0%, transparent 70%)',
        }}
      />
      <Card
        style={{
          position: 'relative', zIndex: 1, width: '100%', maxWidth: width ?? 400,
          background: 'rgba(17,20,27,.72)',
          backdropFilter: 'blur(16px) saturate(140%)',
          border: '1px solid var(--border-strong)',
          boxShadow: '0 24px 80px rgba(0,0,0,.5)',
        }}
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