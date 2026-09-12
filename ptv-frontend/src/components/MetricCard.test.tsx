import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import MetricCard from './MetricCard'

describe('MetricCard', () => {
  it('渲染 label 与 value', () => {
    render(<MetricCard label="在线玩家" value={42} />)
    expect(screen.getByText('在线玩家')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('缺省 value 回退为 0', () => {
    render(<MetricCard label="今日检测" />)
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('accent 存在时渲染语义色条', () => {
    const { container } = render(<MetricCard label="红屏" value={1} accent="var(--kpi-red)" />)
    // 色条是一个 3px 高的绝对定位 span（aria-hidden）
    const bar = container.querySelector('span[aria-hidden="true"]')
    expect(bar).not.toBeNull()
  })

  it('loading 时显示骨架而非数值', () => {
    render(<MetricCard label="作弊记录" value={9} loading />)
    expect(screen.queryByText('9')).not.toBeInTheDocument()
    expect(document.querySelector('.ant-skeleton')).not.toBeNull()
  })

  it('hint 渲染为附加说明', () => {
    render(<MetricCard label="PTEID" value={7} hint="近 24h" />)
    expect(screen.getByText('近 24h')).toBeInTheDocument()
  })
})