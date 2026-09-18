import { create } from 'zustand'
import { applyMotion, isMobileViewport, isTouchDevice, motionAllowed } from '../gsap'

/** 动效强度档位：全关 / 温和 / 标准 / 强烈。 */
export type MotionLevel = 'off' | 'gentle' | 'standard' | 'strong'

/** 可独立开关的装饰性动效。核心入场动效不在此列，始终保留。 */
export type MotionEffect =
  | 'particles'
  | 'scanline'
  | 'glitch'
  | 'cursorGlow'
  | 'tilt3d'
  | 'counter'
  | 'dataStream'
  | 'borderFlow'

/** 红屏警告动效模板：由「温和 → 强烈」逐级叠加闪烁/扩散/故障文字/扫描线。 */
export type RedScreenTemplateId = 'gentle' | 'standard' | 'strong'

export interface MotionConfig {
  level: MotionLevel
  /** 动画速度倍率，>1 更快。性能较差的设备可调低。 */
  speed: number
  effects: Record<MotionEffect, boolean>
  redscreen_template: RedScreenTemplateId
}

export const MOTION_EFFECTS: MotionEffect[] = [
  'particles',
  'scanline',
  'glitch',
  'cursorGlow',
  'tilt3d',
  'counter',
  'dataStream',
  'borderFlow',
]

export const MOTION_EFFECT_LABELS: Record<MotionEffect, string> = {
  particles: '背景粒子',
  scanline: '扫描线',
  glitch: '故障文字',
  cursorGlow: '鼠标跟随光晕',
  tilt3d: '卡片 3D 倾斜',
  counter: '数字滚动',
  dataStream: '数据流粒子',
  borderFlow: '边框光晕流动',
}

export const REDSCREEN_TEMPLATES: Array<{
  id: RedScreenTemplateId
  name: string
  desc: string
  flashCount: number
  expand: boolean
  glitchText: boolean
  scanline: boolean
}> = [
  {
    id: 'gentle',
    name: '温和',
    desc: '仅遮罩渐显，适合易感人群或低性能设备',
    flashCount: 0,
    expand: false,
    glitchText: false,
    scanline: false,
  },
  {
    id: 'standard',
    name: '标准',
    desc: '闪烁 + 遮罩扩散 + 扫描线（默认）',
    flashCount: 4,
    expand: true,
    glitchText: false,
    scanline: true,
  },
  {
    id: 'strong',
    name: '强烈',
    desc: '闪烁 + 扩散 + 故障文字 + 扫描线，冲击力最强',
    flashCount: 6,
    expand: true,
    glitchText: true,
    scanline: true,
  },
]

/** 温和档位下自动关闭的装饰性动效：保留入场，去掉持续循环的视觉噪音。 */
const GENTLE_OFF: MotionEffect[] = ['particles', 'scanline', 'glitch', 'cursorGlow', 'borderFlow', 'dataStream']

export const DEFAULT_MOTION_CONFIG: MotionConfig = {
  level: 'standard',
  speed: 1,
  effects: {
    particles: true,
    scanline: true,
    glitch: true,
    cursorGlow: true,
    tilt3d: true,
    counter: true,
    dataStream: true,
    borderFlow: true,
  },
  redscreen_template: 'standard',
}

/** 归一化后端/持久化下发的原始配置，缺字段一律回落到默认值。 */
export function normalizeMotionConfig(raw: unknown): MotionConfig {
  const src = (raw ?? {}) as Partial<MotionConfig>
  const level: MotionLevel = (['off', 'gentle', 'standard', 'strong'] as const).includes(
    src.level as MotionLevel,
  )
    ? (src.level as MotionLevel)
    : DEFAULT_MOTION_CONFIG.level
  const speedRaw = Number(src.speed)
  const speed = Number.isFinite(speedRaw) && speedRaw > 0 ? Math.min(Math.max(speedRaw, 0.25), 3) : 1
  const effects = { ...DEFAULT_MOTION_CONFIG.effects }
  if (src.effects && typeof src.effects === 'object') {
    for (const key of MOTION_EFFECTS) {
      const v = (src.effects as Record<string, unknown>)[key]
      if (typeof v === 'boolean') effects[key] = v
    }
  }
  const template = REDSCREEN_TEMPLATES.some((t) => t.id === src.redscreen_template)
    ? (src.redscreen_template as RedScreenTemplateId)
    : DEFAULT_MOTION_CONFIG.redscreen_template
  return { level, speed, effects, redscreen_template: template }
}

interface MotionState {
  config: MotionConfig
  /** 是否已从服务端拉取过配置（未拉取时用默认值，不阻塞渲染） */
  loaded: boolean
  setConfig: (raw: unknown) => void
}

export const useMotionStore = create<MotionState>((set) => ({
  config: DEFAULT_MOTION_CONFIG,
  loaded: false,
  setConfig: (raw) => {
    const config = normalizeMotionConfig(raw)
    // 配置变化立即反馈到 gsap 全局默认与 ScrollTrigger 启停
    applyMotion(motionAllowed() && config.level !== 'off', config.speed)
    set({ config, loaded: true })
  },
}))

/** 档位是否允许某项装饰动效：全关一律否，温和按白名单关，标准/强烈看单项开关。 */
export function effectAllowed(config: MotionConfig, effect: MotionEffect): boolean {
  if (!motionAllowed() || config.level === 'off') return false
  if (config.level === 'gentle' && GENTLE_OFF.includes(effect)) return false
  // 触屏/窄屏下 3D 倾斜与鼠标光晕没有意义，直接关闭（§6.1 移动端降级）
  if ((effect === 'tilt3d' || effect === 'cursorGlow') && (isTouchDevice() || isMobileViewport())) return false
  return config.effects[effect]
}

/** 档位对应的动效幅度系数：强烈档放大位移，温和档收敛。 */
export function motionAmplitude(config: MotionConfig): number {
  return config.level === 'strong' ? 1.25 : config.level === 'gentle' ? 0.7 : 1
}

/** 取红屏动效模板定义，未知 id 回落到标准模板。 */
export function redScreenTemplate(config: MotionConfig) {
  return (
    REDSCREEN_TEMPLATES.find((t) => t.id === config.redscreen_template) ??
    REDSCREEN_TEMPLATES[1]
  )
}