import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { gsap, motionAllowed, motionDuration } from '../gsap'

// 按需注册：折线图 / 柱状图 / 饼图 / 热力图 + 必要组件（体积优化）
echarts.use([
  LineChart,
  BarChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  CanvasRenderer,
])

/**
 * ECharts 封装组件：负责实例初始化、容器自适应与销毁。
 * 使用 echarts/core 按需注册，避免整包引入。
 * 首次拿到有数据的结果集时补一次入场（画布随容器一起抬起），之后的数据刷新不再重播。
 */
export default function EChart({ option, height = 260 }: { option: EChartsOption; height?: number | string }) {
  const ref = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)
  const revealed = useRef(false)

  useEffect(() => {
    if (!ref.current) return
    const chart = echarts.init(ref.current)
    chartRef.current = chart
    const onResize = () => chart.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  useEffect(() => {
    chartRef.current?.setOption(option, true)

    if (revealed.current) return
    const series = option.series
    const list = Array.isArray(series) ? series : series ? [series] : []
    const hasData = list.some((s) => {
      const data = (s as { data?: unknown }).data
      return Array.isArray(data) && data.length > 0
    })
    if (!hasData) return
    revealed.current = true
    if (!motionAllowed() || !ref.current) return

    // 只清 transform/opacity：clearProps:'all' 会把 echarts 写在容器上的内联样式一并抹掉
    gsap.fromTo(
      ref.current,
      { opacity: 0, y: 14 },
      {
        opacity: 1,
        y: 0,
        duration: motionDuration(0.55),
        ease: 'power2.out',
        clearProps: 'transform,opacity',
      },
    )
  }, [option])

  return <div ref={ref} style={{ width: '100%', height }} />
}
