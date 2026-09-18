import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { ScrollToPlugin } from 'gsap/ScrollToPlugin'

// 插件注册：ScrollTrigger（滚动驱动入场/视差/进度）、ScrollToPlugin（锚点平滑滚动）
gsap.registerPlugin(ScrollTrigger, ScrollToPlugin)

// 全局默认：克制出场节奏，个别动画按需覆盖 ease/duration
gsap.defaults({ ease: 'power2.out', duration: 0.6 })

/** 系统「减弱动态」偏好查询。用户可在系统设置中随时切换，因此不能只在启动时读取一次。 */
const reducedMotionQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : null

/** 系统层面是否要求减弱动态。 */
export function prefersReducedMotion(): boolean {
  return reducedMotionQuery?.matches ?? false
}

/** 是否为窄屏/移动端视口：用于 §6.1 移动端降级（关 3D 倾斜、减粒子）。 */
export function isMobileViewport(): boolean {
  return typeof window !== 'undefined' && window.innerWidth < 768
}

/** 是否为触屏设备：鼠标跟随光晕、自定义光标在触屏上无意义。 */
export function isTouchDevice(): boolean {
  return typeof window !== 'undefined' && 'ontouchstart' in window
}

let motionEnabled = true
let motionScale = 1

/**
 * 应用动效开关与速度倍率。
 * 两个来源共用此入口：系统 prefers-reduced-motion 偏好，以及管理端「动效配置中心」下发的策略。
 * 关闭时把默认时长压到 1ms 并停用 ScrollTrigger，避免降级模式下仍占用滚动监听与绘制。
 */
export function applyMotion(enabled: boolean, scale = 1): void {
  motionEnabled = enabled
  motionScale = scale > 0 ? scale : 1
  gsap.defaults({ ease: 'power2.out', duration: enabled ? 0.6 / motionScale : 0.01 })
  if (enabled) {
    ScrollTrigger.enable()
  } else {
    ScrollTrigger.disable(false, false)
  }
}

/** 当前是否允许播放大动效（系统偏好与管理端配置同时放行）。 */
export function motionAllowed(): boolean {
  return motionEnabled && !prefersReducedMotion()
}

/** 按当前策略换算动画时长，降级时统一收敛为 1ms。 */
export function motionDuration(seconds: number): number {
  if (!motionAllowed()) return 0.01
  return seconds / motionScale
}

// 启动即按系统偏好初始化
applyMotion(!prefersReducedMotion())

// 系统设置切换到「减弱动态」时同步降级（Safari 14 只有已废弃的 addListener，需兼容）
const syncSystemPreference = (matches: boolean) => applyMotion(!matches, motionScale)
if (reducedMotionQuery) {
  if (typeof reducedMotionQuery.addEventListener === 'function') {
    reducedMotionQuery.addEventListener('change', (e) => syncSystemPreference(e.matches))
  } else if (typeof reducedMotionQuery.addListener === 'function') {
    reducedMotionQuery.addListener((e) => syncSystemPreference(e.matches))
  }
}

export { gsap, ScrollTrigger, ScrollToPlugin }