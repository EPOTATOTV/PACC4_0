/**
 * 品牌标识：logo 像素图 + Russo One 大写品牌词 + 可选中文副标题。
 * 供管理端/玩家端布局、页脚、登录页共用，保证全站品牌视觉一致。
 */
export default function Brand({
  size = 'sm',
  title = 'PACC',
  subtitle,
}: {
  size?: 'xs' | 'sm' | 'md' | 'lg'
  title?: string
  subtitle?: string
}) {
  const px = { xs: 20, sm: 30, md: 56, lg: 96 }[size]
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: size === 'xs' ? 8 : 10 }}>
      <img
        src="/logo.png"
        width={px}
        height={px}
        alt="PACC logo"
        style={{ imageRendering: 'pixelated', display: 'block' }}
      />
      <div>
        <div
          className="brand-font"
          style={{ fontSize: Math.max(13, px / 2.6), lineHeight: 1.1, color: '#ff3b30' }}
        >
          {title}
        </div>
        {subtitle && (
          <div style={{ fontSize: size === 'xs' ? 10 : 11, color: '#8b949e', marginTop: 2 }}>
            {subtitle}
          </div>
        )}
      </div>
    </div>
  )
}
