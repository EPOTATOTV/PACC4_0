import { useRef } from 'react'
import type { ButtonHTMLAttributes, MouseEvent, ReactNode } from 'react'
import { gsap, motionAllowed, motionDuration } from '../../gsap'

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  children: ReactNode
  onClick?: (e: MouseEvent<HTMLButtonElement>) => void
  /** primary：实心品牌色；default：玻璃描边 */
  variant?: 'primary' | 'default'
  /** 波纹基色，默认按 variant 取白/红 */
  rippleColor?: string
}

/**
 * RippleButton —— 点击波纹反馈按钮。
 * 波纹从点击点向外扩散并淡出，只动 transform/opacity；
 * 系统减弱动态时跳过波纹，其余行为与普通按钮一致。
 */
export function RippleButton({
  children,
  onClick,
  className = '',
  variant = 'default',
  rippleColor,
  disabled,
  ...rest
}: Props) {
  const ref = useRef<HTMLButtonElement>(null)

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    onClick?.(e)
    const btn = ref.current
    if (!btn || disabled || !motionAllowed()) return

    const rect = btn.getBoundingClientRect()
    const size = Math.max(rect.width, rect.height) * 2
    const color = rippleColor ?? (variant === 'primary' ? 'rgba(255, 255, 255, .42)' : 'rgba(255, 77, 61, .28)')

    const ripple = document.createElement('span')
    ripple.setAttribute('aria-hidden', 'true')
    ripple.style.cssText =
      `position:absolute;width:${size}px;height:${size}px;border-radius:50%;pointer-events:none;` +
      `left:${e.clientX - rect.left - size / 2}px;top:${e.clientY - rect.top - size / 2}px;` +
      `background:${color};transform:scale(0);`
    btn.appendChild(ripple)

    gsap.to(ripple, {
      scale: 1,
      opacity: 0,
      duration: motionDuration(0.6),
      ease: 'power2.out',
      onComplete: () => ripple.remove(),
    })
  }

  return (
    <button
      ref={ref}
      {...rest}
      disabled={disabled}
      onClick={handleClick}
      className={`pacc-ripple-btn${variant === 'primary' ? ' is-primary' : ''}${className ? ` ${className}` : ''}`}
    >
      {children}
    </button>
  )
}