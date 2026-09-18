import { useEffect, useRef } from 'react'
import { gsap } from '../../gsap'
import { effectAllowed, useMotionStore } from '../../store/motion'

interface Props {
  text: string
  className?: string
  /** 关闭后只渲染静态文本 */
  active?: boolean
}

const REST_SHADOW = '0px 0 rgba(255, 77, 61, 0), 0px 0 rgba(0, 212, 255, 0)'

/**
 * GlitchText —— 赛博朋克风格的文字故障效果。
 * 每 3～8 秒随机触发一次：横向抖动 + 红/青双色分离，持续约 0.6 秒后复位。
 * 禁止高频闪烁，因此不对视觉产生持续干扰；系统减弱动态或关闭故障文字时渲染纯文本。
 */
export function GlitchText({ text, className, active = true }: Props) {
  const ref = useRef<HTMLSpanElement>(null)
  const config = useMotionStore((s) => s.config)
  const enabled = active && effectAllowed(config, 'glitch')

  useEffect(() => {
    const el = ref.current
    if (!el || !enabled) return

    let stopped = false
    let pending: gsap.core.Tween | null = null

    gsap.set(el, { x: 0, textShadow: REST_SHADOW })

    const glitch = () => {
      gsap.fromTo(
        el,
        { x: 0, textShadow: REST_SHADOW },
        {
          x: () => gsap.utils.random(-4, 4),
          textShadow: () =>
            `${gsap.utils.random(-3, 3).toFixed(1)}px 0 rgba(255, 77, 61, .85), ${gsap.utils
              .random(-3, 3)
              .toFixed(1)}px 0 rgba(0, 212, 255, .7)`,
          duration: 0.1,
          repeat: 3,
          yoyo: true,
          ease: 'linear',
          onComplete: () => {
            gsap.set(el, { x: 0, textShadow: REST_SHADOW })
          },
        },
      )
    }

    const schedule = () => {
      if (stopped) return
      pending = gsap.delayedCall(gsap.utils.random(3, 8), () => {
        glitch()
        schedule()
      })
    }
    schedule()

    return () => {
      stopped = true
      pending?.kill()
      gsap.killTweensOf(el)
      gsap.set(el, { x: 0, textShadow: REST_SHADOW, clearProps: 'transform' })
    }
  }, [enabled])

  // 位移类变换对 inline 元素不生效，这里固定为 inline-block；字符串短，不影响换行
  return (
    <span ref={ref} className={className} style={{ display: 'inline-block' }}>
      {text}
    </span>
  )
}