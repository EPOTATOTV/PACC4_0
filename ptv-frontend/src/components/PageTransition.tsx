import type { ReactNode } from 'react'
import { useLocation, useOutlet } from 'react-router-dom'
import { gsap, motionAllowed, motionDuration } from '../gsap'
import { useGSAP } from '../hooks/useGSAP'

interface Props {
  /** 显式传入页面内容；不传时取当前路由的 Outlet（用于嵌套路由场景） */
  children?: ReactNode
}

/**
 * PageTransition —— 路由切换时的统一入场动画。
 *
 * 每次 pathname 变化都重建动画上下文：内容从下方 14px 处淡入并回正，
 * 只动 transform/opacity，不触发重排。系统要求减弱动态时不介入，
 * 直接渲染内容（内容不会停留在隐藏态）。
 */
export default function PageTransition({ children }: Props) {
  const location = useLocation()
  const outlet = useOutlet()
  const content = children ?? outlet

  const ref = useGSAP<HTMLDivElement>(({ el }) => {
    if (!motionAllowed()) return
    // 入场：阻尼出场上移，位移克制（大面板的「被推到眼前」而非跳动）
    gsap.fromTo(
      el,
      { y: 14, opacity: 0 },
      { y: 0, opacity: 1, duration: motionDuration(0.42), ease: 'power2.out', clearProps: 'all' },
    )
  }, [location.pathname])

  return (
    <div ref={ref} key={location.pathname}>
      {content}
    </div>
  )
}