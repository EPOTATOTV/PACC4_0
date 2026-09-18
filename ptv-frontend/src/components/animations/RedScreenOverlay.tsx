import { useEffect, useMemo, useRef } from 'react'
import { gsap, motionAllowed, motionDuration } from '../../gsap'
import { motionAmplitude, redScreenTemplate, useMotionStore } from '../../store/motion'
import { ScanLine } from './ScanLine'
import { GlitchText } from './GlitchText'

export interface RedScreenDetailItem {
  label: string
  value: string
}

interface Props {
  /** 打开时才播放入场序列；关闭后组件仍保留在 DOM 中以便复用同一套动画上下文 */
  open: boolean
  title?: string
  /** 次要标签行，仅在「强烈」模板下叠加故障文字 */
  stamp?: string
  details?: RedScreenDetailItem[]
  onClose: () => void
}

/**
 * RedScreenOverlay —— 红屏警告全屏覆盖层（§4.4.1 入场序列）。
 *
 * 序列：闪烁 → 遮罩从中心扩散 → 警告文字逐字翻入 → 详情滑入 → 扫描线常驻。
 * 各阶段由「红屏动效模板」控制（温和只渐显、标准加闪烁与扩散、强烈再叠加故障文字与内阴影脉冲）。
 * 系统要求减弱动态时整段跳过，直接呈现终态——闪烁类效果对光敏感人群有风险，不做降级版本。
 */
export function RedScreenOverlay({
  open,
  title = '检测到作弊行为',
  stamp = 'CHEAT DETECTED',
  details = [],
  onClose,
}: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const flashRef = useRef<HTMLDivElement>(null)
  const maskRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const detailsRef = useRef<HTMLDivElement>(null)

  const config = useMotionStore((s) => s.config)
  const template = redScreenTemplate(config)
  // 手动拆分文字：项目未引入付费的 SplitText 插件（§7.2 用开源方案替代）
  const chars = useMemo(() => Array.from(title), [title])

  useEffect(() => {
    if (!open) return
    const mask = maskRef.current
    const flash = flashRef.current
    const titleEl = titleRef.current
    const detailsEl = detailsRef.current
    if (!mask || !flash || !titleEl) return

    // 减弱动态 / 动效全关：不闪烁、不扩散，直接显示静态红屏
    if (!motionAllowed() || config.level === 'off') return

    const amp = motionAmplitude(config)
    const tl = gsap.timeline()

    // 1. 屏幕闪烁：交替压上高亮层，次数由模板决定（温和档为 0）
    for (let i = 0; i < template.flashCount; i += 1) {
      tl.to(flash, {
        opacity: i % 2 === 0 ? 0.82 : 0,
        duration: motionDuration(0.05),
        ease: 'none',
      })
    }
    if (template.flashCount > 0) {
      tl.to(flash, { opacity: 0, duration: motionDuration(0.12), ease: 'none' })
    }

    // 2. 遮罩从中心扩散：整体 scale 放到 3 倍并撤掉圆角，形成「红潮铺满屏幕」
    if (template.expand) {
      tl.fromTo(
        mask,
        { scale: 0, opacity: 0, borderRadius: '50%' },
        {
          scale: 3 * amp,
          opacity: 1,
          borderRadius: 0,
          duration: motionDuration(0.6),
          ease: 'power4.in',
        },
        '+=0.06',
      )
    } else {
      tl.fromTo(mask, { opacity: 0 }, { opacity: 1, duration: motionDuration(0.42) }, '+=0.06')
    }

    // 3. 警告文字逐字翻入（绕 X 轴翻起，错峰比闪烁慢得多，避免叠加成高频刺激）
    tl.from(
      titleEl.querySelectorAll('.pacc-rs-char'),
      {
        y: -46,
        opacity: 0,
        rotateX: 90,
        duration: motionDuration(0.5),
        stagger: motionDuration(0.05),
        ease: 'back.out(2)',
        clearProps: 'all',
      },
      '-=0.1',
    )

    // 4. 检测详情自左侧滑入
    if (detailsEl && details.length > 0) {
      tl.from(
        detailsEl.children,
        {
          x: -46,
          opacity: 0,
          duration: motionDuration(0.45),
          stagger: motionDuration(0.05),
          ease: 'power2.out',
          clearProps: 'all',
        },
        '-=0.24',
      )
    }

    // 5. 内阴影脉冲：仅「强烈」模板启用，且次数有限，不做无限呼吸
    if (template.id === 'strong') {
      tl.fromTo(
        mask,
        { boxShadow: 'inset 0 0 0 rgba(255, 0, 0, 0)' },
        {
          boxShadow: 'inset 0 0 100px rgba(255, 0, 0, .5)',
          duration: motionDuration(1),
          repeat: 2,
          yoyo: true,
          ease: 'sine.inOut',
          clearProps: 'boxShadow',
        },
        '-=0.2',
      )
    }

    return () => {
      tl.kill()
    }
  }, [open, config, template, details.length])

  // Esc 关闭：红屏是强制打断，必须给键盘留出口
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  return (
    <div ref={rootRef} className="pacc-rs" role="alertdialog" aria-modal="true" aria-label={title}>
      <div ref={flashRef} className="pacc-rs-flash" aria-hidden />
      <div ref={maskRef} className="pacc-rs-mask" aria-hidden />
      <div className="pacc-rs-body">
        {template.scanline && <ScanLine speed={2.4} color="rgba(255, 255, 255, .28)" thickness={2} />}
        <div className="pacc-rs-stamp mono">
          <span className="pacc-rs-dot" aria-hidden />
          {template.glitchText ? <GlitchText text={stamp} /> : stamp}
        </div>
        <h1 ref={titleRef} className="pacc-rs-title">
          {chars.map((ch, i) => (
            <span key={`${ch}-${i}`} className="pacc-rs-char">
              {ch}
            </span>
          ))}
        </h1>
        {details.length > 0 && (
          <div ref={detailsRef} className="pacc-rs-details">
            {details.map((d) => (
              <div key={d.label} className="pacc-rs-detail">
                <span className="pacc-rs-detail-label">{d.label}</span>
                <span className="pacc-rs-detail-value">{d.value}</span>
              </div>
            ))}
          </div>
        )}
        <button type="button" className="pacc-rs-close" onClick={onClose}>
          解除红屏 (Esc)
        </button>
      </div>
    </div>
  )
}