/**
 * 组件包内部共享的一小撮展示件。
 * 只做样式约定，不含业务逻辑。
 */
import type { CSSProperties, ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'gold' | 'error' | 'warning' | 'ok' | 'muted'

export function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={`prl-badge prl-badge--${tone}`}>{children}</span>
}

export interface PanelProps {
  title?: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  /** 错峰入场的位次，决定动画延迟。 */
  index?: number
}

export function Panel({ title, subtitle, actions, children, className, index = 0 }: PanelProps) {
  const style: CSSProperties = { animationDelay: `${index * 60}ms` }
  return (
    <section className={`prl-panel${className ? ` ${className}` : ''}`} style={style}>
      {(title || actions) && (
        <header className="prl-panel__head">
          <div className="prl-panel__titles">
            {title && <h2 className="prl-panel__title">{title}</h2>}
            {subtitle && <p className="prl-panel__subtitle">{subtitle}</p>}
          </div>
          {actions && <div className="prl-panel__actions">{actions}</div>}
        </header>
      )}
      <div className="prl-panel__body">{children}</div>
    </section>
  )
}

export interface EmptyStateProps {
  title: string
  hint?: string
  action?: ReactNode
  tone?: 'neutral' | 'error'
}

export function EmptyState({ title, hint, action, tone = 'neutral' }: EmptyStateProps) {
  return (
    <div className={`prl-empty prl-empty--${tone}`}>
      <p className="prl-empty__title">{title}</p>
      {hint && <p className="prl-empty__hint">{hint}</p>}
      {action && <div className="prl-empty__action">{action}</div>}
    </div>
  )
}

/** 演示数据的统一标记。凡是前端内置的假数据都必须挂这个标签。 */
export function DemoMark({ children = '演示数据' }: { children?: ReactNode }) {
  return <span className="prl-demo-mark">{children}</span>
}

export function InlineError({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="prl-inline-error" role="alert">
      <span className="prl-inline-error__text">{message}</span>
      {action}
    </div>
  )
}