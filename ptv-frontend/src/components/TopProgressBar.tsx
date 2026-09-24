import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { gsap, motionAllowed, motionDuration } from '../gsap'

/**
 * 路由切换进度条（顶部 2px 细线）。
 *
 * 不做假计时：切路由时先爬到 80%，等新页面真正完成一帧绘制（两次 rAF）后再补到 100% 并淡出。
 * 页面越慢、绘制越晚，80% 停留得越久——进度条反映的是真实等待，而不是固定时长演出。
 * 系统「减弱动态」下直接不显示：2px 高频闪动对这类用户是干扰而非信息。
 */
export default function TopProgressBar() {
  const { pathname } = useLocation()
  const barRef = useRef<HTMLDivElement>(null)
  const tlRef = useRef<gsap.core.Timeline | null>(null)
  const booted = useRef(false)

  useEffect(() => {
    const el = barRef.current
    if (!el) return
    // 首屏不做进度条：此时还没有“切换”，显示反而会被误读为卡顿
    if (!booted.current) {
      booted.current = true
      return
    }
    if (!motionAllowed()) return

    tlRef.current?.kill()
    const tl = gsap.timeline({
      onComplete: () => {
        gsap.set(el, { opacity: 0, scaleX: 0 })
      },
    })
    tlRef.current = tl

    tl.set(el, { opacity: 1, scaleX: 0 }).to(el, {
      scaleX: 0.8,
      duration: motionDuration(0.34),
      ease: 'power2.out',
    })

    // 等新路由的内容提交并绘制完一帧，再收尾
    let raf2 = 0
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        tl.to(el, { scaleX: 1, duration: motionDuration(0.22), ease: 'power1.inOut' })
          .to(el, { opacity: 0, duration: motionDuration(0.3), ease: 'power1.out' }, '+=0.06')
      })
    })

    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [pathname])

  useEffect(
    () => () => {
      tlRef.current?.kill()
    },
    [],
  )

  return <div ref={barRef} className="pacc-topbar" aria-hidden="true" />
}