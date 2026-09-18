import { useEffect, useRef } from 'react'
import { gsap } from '../../gsap'
import { effectAllowed, useMotionStore } from '../../store/motion'

interface Props {
  /** horizontal：横向亮线自上而下扫过；vertical：纵向亮线自左向右扫过 */
  direction?: 'horizontal' | 'vertical'
  /** 单次扫描耗时（秒），默认 3 */
  speed?: number
  color?: string
  /** 亮线粗细（px），默认 2 */
  thickness?: number
  /** container：在最近的定位父元素内往复；viewport：整屏往复 */
  mode?: 'container' | 'viewport'
}

/**
 * ScanLine —— 线性扫描线。
 * 用 linear 缓动做匀速运动，营造机械精确的检测感；关闭时不渲染任何 DOM，不占用主循环。
 */
export function ScanLine({
  direction = 'horizontal',
  speed = 3,
  color = 'rgba(255, 77, 61, .4)',
  thickness = 2,
  mode = 'container',
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const config = useMotionStore((s) => s.config)
  const enabled = effectAllowed(config, 'scanline')

  useEffect(() => {
    const el = ref.current
    if (!el || !enabled) return

    const axis = direction === 'horizontal' ? 'y' : 'x'
    // 行程按运行时尺寸函数计算，配合 repeatRefresh 每轮重新取值，窗口缩放后无需重启动画
    const travel = () =>
      (mode === 'viewport' ? window.innerHeight : (el.parentElement?.clientHeight ?? window.innerHeight)) + thickness

    const fromVars: gsap.TweenVars = { [axis]: -thickness }
    const toVars: gsap.TweenVars = {
      [axis]: travel,
      duration: speed,
      ease: 'linear',
      repeat: -1,
      repeatRefresh: true,
    }
    const tween = gsap.fromTo(el, fromVars, toVars)

    return () => {
      tween.kill()
      gsap.set(el, { clearProps: 'all' })
    }
  }, [direction, speed, thickness, mode, enabled])

  if (!enabled) return null

  const horizontal = direction === 'horizontal'
  return (
    <div
      ref={ref}
      aria-hidden
      style={{
        position: 'absolute',
        pointerEvents: 'none',
        zIndex: 5,
        left: horizontal ? 0 : undefined,
        right: horizontal ? 0 : undefined,
        top: horizontal ? undefined : 0,
        bottom: horizontal ? undefined : 0,
        height: horizontal ? thickness : undefined,
        width: horizontal ? undefined : thickness,
        background: horizontal
          ? `linear-gradient(to bottom, transparent, ${color}, transparent)`
          : `linear-gradient(to right, transparent, ${color}, transparent)`,
        opacity: 0.9,
      }}
    />
  )
}