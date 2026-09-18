import { useEffect, useRef } from 'react'
import { gsap, isMobileViewport } from '../../gsap'
import { effectAllowed, useMotionStore } from '../../store/motion'

interface Props {
  /** 粒子数；不传时按视口取默认（桌面 22 / 移动 10） */
  count?: number
  /** 单次下落基准耗时（秒），实际每颗粒子在 0.5x～1.5x 间随机，默认 2 */
  speed?: number
  color?: string
  /** container：在最近的定位父元素内下落；viewport：整屏下落 */
  mode?: 'container' | 'viewport'
}

/**
 * DataStream —— 线性数据流粒子。
 * 粒子匀速下落（linear），模拟检测数据在管道中流动；粒子数在移动端减半。
 * 关闭时组件不渲染，也不会创建任何 DOM，避免离屏空转。
 */
export function DataStream({ count, speed = 2, color = '#ff4d3d', mode = 'container' }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const config = useMotionStore((s) => s.config)
  const enabled = effectAllowed(config, 'dataStream')
  const total = count ?? (isMobileViewport() ? 10 : 22)

  useEffect(() => {
    const container = ref.current
    if (!container || !enabled) return

    const created: HTMLElement[] = []
    const ctx = gsap.context(() => {
      const travel = () =>
        (mode === 'viewport' ? window.innerHeight : container.clientHeight) + 80

      for (let i = 0; i < total; i += 1) {
        const p = document.createElement('span')
        p.style.cssText =
          `position:absolute;width:2px;height:${gsap.utils.random(14, 44).toFixed(0)}px;` +
          `left:${gsap.utils.random(0, 100).toFixed(2)}%;top:-60px;` +
          `background:linear-gradient(to bottom, transparent, ${color}, transparent);` +
          `opacity:${gsap.utils.random(0.2, 0.7).toFixed(2)};`
        container.appendChild(p)
        created.push(p)

        gsap.to(p, {
          y: travel,
          duration: () => gsap.utils.random(speed * 0.5, speed * 1.5),
          ease: 'linear',
          repeat: -1,
          repeatRefresh: true,
          delay: gsap.utils.random(0, speed * 2),
        })
      }
    }, container)

    return () => {
      // 先移除命令式创建的节点，再 revert 内联样式与补间
      created.forEach((el) => el.remove())
      ctx.revert()
    }
  }, [total, speed, color, mode, enabled])

  if (!enabled) return null

  return (
    <div
      ref={ref}
      aria-hidden
      style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none', zIndex: 0 }}
    />
  )
}