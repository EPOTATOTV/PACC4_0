import { useCallback, useLayoutEffect, useRef } from 'react'
import type { DependencyList, RefObject } from 'react'
import { gsap, motionAllowed } from '../gsap'

/** 事件回调包装器：把回调内创建的动画纳入同一上下文，卸载时一并清理。 */
export type ContextSafe = <A extends unknown[]>(fn: (...args: A) => void) => (...args: A) => void

/** useGSAP 回调入参。 */
export interface GSAPScope<T extends HTMLElement = HTMLElement> {
  /** 组件根元素：作为选择器作用域与动画对象。 */
  el: T
  /** gsap 上下文：用于创建受管 timeline，或在卸载时手动 revert。 */
  context: gsap.Context
  /** 包裹事件回调，使回调中创建的动画同样受上下文生命周期管理。 */
  contextSafe: ContextSafe
}

/**
 * useGSAP —— React 友好的 GSAP 生命周期封装。
 *
 * 挂载后执行动画，卸载时 `ctx.revert()` 还原全部补间与内联样式，
 * 因此 StrictMode 下重复挂载/卸载不会残留动画或错误的内联样式。
 * 动画选择器默认以返回的 ref 元素为作用域。
 *
 * 回调可返回一个清理函数，gsap 会在 revert 时调用它（用于解绑非 React 的监听器）。
 *
 * @param callback 动画回调，仅在挂载与 deps 变化时执行
 * @param deps 依赖数组，语义与 useEffect 一致
 */
export function useGSAP<T extends HTMLElement = HTMLDivElement>(
  callback: (scope: GSAPScope<T>) => void | (() => void),
  deps: DependencyList = [],
): RefObject<T> {
  const ref = useRef<T>(null)

  // 用 layout effect 而非 passive effect：补间的初始态在浏览器绘制前就写入，
  // 否则入场动画会先闪一帧终态再跳回起点。
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return

    const ctx = gsap.context((context, contextSafe) => {
      // gsap 把 contextSafe 声明为 (Function) => Function，这里收敛为保留入参/返回类型的签名，
      // 调用方才能直接拿到可用的回调类型，无需在每处事件绑定再断言一次。
      return callback({ el, context, contextSafe: contextSafe as unknown as ContextSafe })
    }, el)

    return () => ctx.revert()
    // callback 的依赖由调用方通过 deps 显式声明，故此处不再把 callback 计入依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return ref
}

export interface ScrollRevealOptions {
  /** 纵向位移初值（px），默认 40 */
  y?: number
  /** 起始透明度，默认 0 */
  opacity?: number
  /** 单元素时长（秒），默认 0.8 */
  duration?: number
  /** 交错间隔（秒），默认 0.1 */
  stagger?: number
  /** 起始延迟（秒），默认 0 */
  delay?: number
  /** 缓动函数，默认 power3.out */
  ease?: string
  /** ScrollTrigger 触发起点，默认 top 85% */
  start?: string
  /** 离屏行为，默认 reverse（回滚时还原） */
  toggleActions?: string
}

/**
 * useScrollReveal —— 滚动入场动画。
 * 优先动画根元素内标记了 `data-reveal` 的子元素（用于交错），没有标记则动画根元素本身。
 * 系统要求减弱动态时直接返回，内容保持默认可见，不做隐藏。
 */
export function useScrollReveal<T extends HTMLElement = HTMLDivElement>(
  options: ScrollRevealOptions = {},
): RefObject<T> {
  const {
    y = 40,
    opacity = 0,
    duration = 0.8,
    stagger = 0.1,
    delay = 0,
    ease = 'power3.out',
    start = 'top 85%',
    toggleActions = 'play none none reverse',
  } = options
  const ref = useRef<T>(null)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el || !motionAllowed()) return

    const ctx = gsap.context(() => {
      const marked = el.querySelectorAll<HTMLElement>('[data-reveal]')
      const targets: Element[] = marked.length > 0 ? Array.from(marked) : [el]
      gsap.from(targets, {
        y,
        opacity,
        duration,
        stagger,
        delay,
        ease,
        scrollTrigger: { trigger: el, start, toggleActions },
      })
    }, el)

    return () => ctx.revert()
  }, [y, opacity, duration, stagger, delay, ease, start, toggleActions])

  return ref
}

export interface TableRowRevealOptions {
  /** 每行时长（秒），默认 0.3 */
  duration?: number
  /** 行间交错（秒），默认 0.03 */
  stagger?: number
  /** 横向位移初值（px），默认 -20 */
  x?: number
}

/**
 * useTableRowReveal —— 数据表格行逐个淡入。
 * 在数据到达后调用 `reveal(rowCount)`，对容器内当前已渲染的 `.ant-table-row` 播放入场；
 * 结束即 clearProps，避免残留内联样式影响 antd 自身的 hover/展开动画。
 */
export function useTableRowReveal<T extends HTMLElement = HTMLDivElement>(
  options: TableRowRevealOptions = {},
): { ref: RefObject<T>; reveal: () => void } {
  const { duration = 0.3, stagger = 0.03, x = -20 } = options
  const ref = useRef<T>(null)

  // 稳定引用：调用方通常把它写进 effect 依赖，每次渲染都换新的会反复触发入场
  const reveal = useCallback(() => {
    const el = ref.current
    if (!el || !motionAllowed()) return
    const rows = el.querySelectorAll('.ant-table-row')
    if (rows.length === 0) return
    gsap.from(rows, {
      x,
      opacity: 0,
      duration,
      stagger,
      ease: 'power2.out',
      clearProps: 'all',
    })
  }, [duration, stagger, x])

  return { ref, reveal }
}

export interface ModalRevealTargets {
  /** 弹层主体选择器，默认 '.ant-modal' 或 '.ant-drawer-content' 二选一 */
  panel?: string
  /** 遮罩选择器 */
  mask?: string
}

/**
 * useModalReveal —— 替代 antd 默认弹层入场：弹性放大 + 遮罩淡入。
 * 在弹层 `open` 状态翻转为 true 后调用效果，需在弹层完成挂载的下一帧执行。
 */
export function useModalReveal(options: ModalRevealTargets = {}): () => void {
  const { panel = '.ant-modal', mask = '.ant-modal-mask' } = options
  // 稳定引用：调用方把它写进「弹层打开」的 effect 依赖，避免每次渲染都重放
  return useCallback(() => {
    if (!motionAllowed()) return
    const panels = document.querySelectorAll(panel)
    if (panels.length > 0) {
      gsap.fromTo(
        panels,
        { scale: 0.9, y: 20, opacity: 0 },
        { scale: 1, y: 0, opacity: 1, duration: 0.4, ease: 'back.out(1.7)', clearProps: 'all' },
      )
    }
    const masks = document.querySelectorAll(mask)
    if (masks.length > 0) {
      gsap.fromTo(masks, { opacity: 0 }, { opacity: 1, duration: 0.3, clearProps: 'all' })
    }
  }, [panel, mask])
}