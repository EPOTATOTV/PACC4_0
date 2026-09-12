import type { CSSProperties, ReactNode } from 'react'
import { Card, Skeleton, Statistic } from 'antd'

/**
 * 统一的运营台 / 玩家端 KPI 统计卡。
 * 替换各处重复的「Card size="small" + Statistic + 强调色」内联写法，收敛视觉一致性。
 * 顶部带 3px 语义色条（默认随 accent，不传则用弱边框），hover 仅提亮边框/阴影，克制不动效。
 * 视觉：深一档底、发丝内边、紧凑字距，避免“一屏全卡片”的单调感。
 */
export default function MetricCard({
  label,
  value,
  accent,
  hint,
  loading,
  style,
}: {
  label: string
  value?: number | string
  accent?: string
  hint?: ReactNode
  loading?: boolean
  style?: CSSProperties
}) {
  return (
    <Card
      size="small"
      className="metric-card"
      style={{
        position: 'relative',
        overflow: 'hidden',
        background: 'rgba(255,255,255,.02)',
        border: '1px solid var(--border)',
        ...style,
      }}
      styles={{ body: { padding: '14px 16px 12px' } }}
    >
      {accent && (
        <span
          aria-hidden
          style={{
            position: 'absolute',
            insetInlineStart: 0,
            insetBlockStart: 0,
            width: 3,
            height: '100%',
            background: `linear-gradient(180deg, ${accent}, ${accent}55)`,
          }}
        />
      )}
      {loading ? (
        <Skeleton active title paragraph={false} style={{ width: '80%' }} />
      ) : (
        <Statistic
          title={label}
          value={value ?? 0}
          styles={{ content: { color: accent, fontWeight: 700 } }}
          style={{ '--label-mb': '4px' } as CSSProperties}
        />
      )}
      {hint && <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 6 }}>{hint}</div>}
    </Card>
  )
}