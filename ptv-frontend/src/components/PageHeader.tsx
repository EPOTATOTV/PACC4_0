import type { ReactNode } from 'react'
import { Alert, Typography } from 'antd'

const { Title } = Typography

/**
 * 统一的页面标题行：标题 + 可选右操作区。错误提示用可关闭 Alert，关闭回调清除状态。
 * 替代各页重复的「flex + Title + 右侧操作 + 错误」内联头行。
 */
export default function PageHeader({
  title,
  description,
  extra,
  error,
  onCloseError,
  style,
}: {
  title: string
  description?: ReactNode
  extra?: ReactNode
  error?: string
  onCloseError?: () => void
  style?: React.CSSProperties
}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 14,
        marginBottom: 16,
        flexWrap: 'wrap',
        ...style,
      }}
    >
      <Title level={3} style={{ margin: 0 }}>{title}</Title>
      {description && <span style={{ fontSize: 12, color: 'var(--muted)' }}>{description}</span>}
      <div style={{ flex: 1 }} />
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          style={{ flex: 1, minWidth: 200 }}
          closable={!!onCloseError}
          onClose={onCloseError}
        />
      )}
      {extra}
    </div>
  )
}