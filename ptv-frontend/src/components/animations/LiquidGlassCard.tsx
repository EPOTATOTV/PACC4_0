import { useRef } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { gsap, motionDuration } from '../../gsap'
import { useGSAP } from '../../hooks/useGSAP'
import { effectAllowed, useMotionStore } from '../../store/motion'

interface Props {
  children: ReactNode
  className?: string
  /** 鼠标跟随光晕的基色 */
  glowColor?: string
  /** 玻璃层级：决定圆角与投影深浅，与全站 pacc-glass-lg/md/sm 分级一致 */
  size?: 'lg' | 'md' | 'sm'
  /** 内容内距；不传则按 size 取分级内距 */
  padding?: number | string
  style?: CSSProperties
}

const PADDING: Record<'lg' | 'md' | 'sm', string> = {
  lg: '20px 22px',
  md: '16px 18px',
  sm: '11px 14px',
}

/**
 * LiquidGlassCard —— 液态玻璃卡片。
 * 玻璃视觉由 .pacc-glass 提供（其 box-shadow 带 !important，动画须作用在独立叠加层），
 * 因此结构上拆成三层：玻璃底、鼠标跟随光晕、悬停描边光。
 *
 * 悬停只做「描边转红 + 光晕跟随」，不做位移/缩放，避免满屏跳动。
 * 触屏或关闭鼠标光晕时退化为纯玻璃卡片。
 */
export function LiquidGlassCard({
  children,
  className = '',
  glowColor = 'rgba(255, 77, 61, .16)',
  size = 'md',
  padding,
  style,
}: Props) {
  const config = useMotionStore((s) => s.config)
  const glowEnabled = effectAllowed(config, 'cursorGlow') || effectAllowed(config, 'borderFlow')
  const handlers = useRef<{ enter: () => void; leave: () => void; move: (e: MouseEvent) => void }>({
    enter: () => {},
    leave: () => {},
    move: () => {},
  })

  const ref = useGSAP<HTMLDivElement>(({ el, contextSafe }) => {
    const glow = el.querySelector<HTMLElement>('.pacc-lgc-glow')
    const halo = el.querySelector<HTMLElement>('.pacc-lgc-halo')

    handlers.current.enter = contextSafe(() => {
      if (halo) gsap.to(halo, { opacity: 1, duration: motionDuration(0.3), ease: 'power2.out' })
      if (glow && glowEnabled) gsap.to(glow, { opacity: 1, duration: motionDuration(0.3) })
    })
    handlers.current.leave = contextSafe(() => {
      if (halo) gsap.to(halo, { opacity: 0, duration: motionDuration(0.36), ease: 'power2.out' })
      if (glow) gsap.to(glow, { opacity: 0, duration: motionDuration(0.3) })
    })
    handlers.current.move = contextSafe((e: MouseEvent) => {
      if (!glow || !glowEnabled) return
      const rect = el.getBoundingClientRect()
      gsap.to(glow, {
        x: e.clientX - rect.left - rect.width / 2,
        y: e.clientY - rect.top - rect.height / 2,
        duration: motionDuration(0.32),
        ease: 'power2.out',
      })
    })

    el.addEventListener('mouseenter', handlers.current.enter)
    el.addEventListener('mouseleave', handlers.current.leave)
    el.addEventListener('mousemove', handlers.current.move)
    return () => {
      el.removeEventListener('mouseenter', handlers.current.enter)
      el.removeEventListener('mouseleave', handlers.current.leave)
      el.removeEventListener('mousemove', handlers.current.move)
    }
  }, [glowEnabled])

  return (
    <div ref={ref} className={`pacc-lgc pacc-glass-${size} ${className}`} style={style}>
      <div className={`pacc-lgc-glass pacc-glass pacc-glass-${size}`} aria-hidden />
      <div className="pacc-lgc-glow" aria-hidden style={{ background: `radial-gradient(circle, ${glowColor}, transparent 70%)` }} />
      <div className="pacc-lgc-halo pacc-glass-sm" aria-hidden />
      <div className="pacc-lgc-body" style={{ padding: padding ?? PADDING[size] }}>
        {children}
      </div>
    </div>
  )
}