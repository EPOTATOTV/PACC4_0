import { useEffect, useRef, useState } from 'react'
import { gsap, motionDuration } from '../../gsap'
import { effectAllowed, useMotionStore } from '../../store/motion'

interface Props {
  value: number
  /** 滚动时长（秒），默认 2 */
  duration?: number
  /** 起始延迟（秒），用于同屏多个数字交错滚动 */
  delay?: number
  /** 保留小数位，默认 0 */
  decimals?: number
  suffix?: string
  prefix?: string
  className?: string
}

/**
 * AnimatedCounter —— 数字从 0 滚动到目标值。
 *
 * 动效关闭或系统要求减弱动态时直接渲染终值，不进滚动分支；
 * 插值放在普通对象上，只在 onUpdate 回调里同步 React 状态。
 */
export function AnimatedCounter({ value, duration = 2, delay = 0, decimals = 0, suffix = '', prefix = '', className }: Props) {
  const config = useMotionStore((s) => s.config)
  const animated = effectAllowed(config, 'counter')
  const [rolling, setRolling] = useState(0)
  // 用普通对象承载插值，避免每帧构造新的动画目标
  const box = useRef({ val: 0 })
  // 首轮才走完整的「从 0 滚到目标值」；后续数值刷新（如自动轮询）只做短促过渡，不重复错峰延迟
  const firstRun = useRef(true)

  useEffect(() => {
    if (!animated) return
    const initial = firstRun.current
    let done = false
    const tween = gsap.to(box.current, {
      val: value,
      delay: initial ? motionDuration(delay) : 0,
      duration: motionDuration(initial ? duration : 0.6),
      ease: 'power2.out',
      onUpdate: () => setRolling(box.current.val),
      onComplete: () => {
        done = true
        firstRun.current = false
      },
    })
    return () => {
      // 未跑完就被中断（StrictMode 的开发期重挂）时恢复首轮语义，延迟与时长不会被吃掉
      if (initial && !done) firstRun.current = true
      tween.kill()
    }
  }, [value, duration, delay, animated])

  const display = animated ? rolling : value

  return (
    <span className={className}>
      {prefix}
      {display.toFixed(decimals)}
      {suffix}
    </span>
  )
}