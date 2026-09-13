import type { ReactNode } from 'react'
import { Card } from 'antd'
import Brand from '../../components/Brand'

/**
 * 玩家门户认证页统一外壳：液体玻璃卡片 + 背后彩色光斑 + 品牌 logo。
 * 登录 / 注册 / 找回密码公用，保证三页视觉一致，避免各自复制卡片样式。
 *
 * 玻璃做法的关键：
 *  - 背后必须有一层可模糊的彩色光斑（blur 才有参照物）
 *  - 卡片低不透明度 + blur + saturate，呈现真正的透光折射
 *  - 顶部内高光与边缘折射光，模拟“液滴”厚度
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
      {/* 环境氛围：底部斜向彩色光斑（给玻璃提供可模糊的色彩）+ 顶部红光 */}
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden',
          background:
            'radial-gradient(680px 420px at 50% -120px, rgba(255,77,61,.34), transparent 62%), var(--bg)',
        }}
      />
      <div
        aria-hidden
        className="pacc-aurora"
        style={{
          position: 'fixed', inset: 0, zIndex: 0, opacity: .95,
          background:
            'radial-gradient(520px 360px at 78% 88%, rgba(93,169,255,.40), transparent 66%),' +
            'radial-gradient(460px 340px at 12% 82%, rgba(63,185,80,.34), transparent 66%),' +
            'radial-gradient(360px 320px at 82% 18%, rgba(255,77,61,.38), transparent 60%),' +
            'radial-gradient(300px 280px at 22% 18%, rgba(226,162,55,.28), transparent 60%)',
          filter: 'blur(0px)',
        }}
      />
      {/* 玻璃下散布的细颗粒，强化液体质感且不干扰可读性 */}
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, opacity: .26,
          backgroundImage:
            'radial-gradient(rgba(255,255,255,.5) 1px, transparent 1px)',
          backgroundSize: '34px 34px',
          maskImage: 'radial-gradient(circle at 50% 50%, #000 0%, transparent 78%)',
          WebkitMaskImage: 'radial-gradient(circle at 50% 50%, #000 0%, transparent 78%)',
        }}
      />
      <Card
        className="pacc-glass"
        style={{
          position: 'relative', overflow: 'hidden', zIndex: 1, width: '100%', maxWidth: width ?? 400,
          background: 'linear-gradient(160deg, rgba(255,255,255,.14), rgba(255,255,255,.04) 42%), rgba(11,14,19,.34)',
          backdropFilter: 'blur(22px) saturate(180%)',
          WebkitBackdropFilter: 'blur(22px) saturate(180%)',
          boxShadow:
            'inset 0 1px 0 rgba(255,255,255,.14), inset 0 -1px 0 rgba(255,255,255,.04), 0 24px 80px rgba(0,0,0,.5)',
        }}
        styles={{ body: { padding: 28 } }}
      >
        {/* 顶部折射高光带（液滴反光） */}
        <span
          aria-hidden
          style={{
            position: 'absolute', top: 0, left: 0, right: 0, height: 1,
            background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.6), transparent)',
          }}
        />
        {/* 品牌红渐变细线 */}
        <span
          aria-hidden
          style={{
            position: 'absolute', top: 0, left: 0, right: 0, height: 3,
            background: 'linear-gradient(90deg, #ff3b30, rgba(255,59,48,0))',
            opacity: .85,
          }}
        />
        <Brand size="md" title={title} subtitle={subtitle} />

        {children}

        {footer && (
          <div style={{ marginTop: 18, textAlign: 'center' }}>{footer}</div>
        )}
      </Card>
    </div>
  )
}