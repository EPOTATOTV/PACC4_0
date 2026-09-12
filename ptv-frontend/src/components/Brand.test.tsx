import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import Brand from './Brand'

describe('Brand', () => {
  it('渲染默认标题 PACC', () => {
    render(<Brand />)
    expect(screen.getByText('PACC')).toBeInTheDocument()
  })

  it('渲染自定义标题与副标题', () => {
    render(<Brand title="PTV" subtitle="反作弊运营台" />)
    expect(screen.getByText('PTV')).toBeInTheDocument()
    expect(screen.getByText('反作弊运营台')).toBeInTheDocument()
  })

  it('渲染 logo 图片', () => {
    render(<Brand />)
    const img = document.querySelector('img[alt="PACC logo"]') as HTMLImageElement
    expect(img).not.toBeNull()
    expect(img.src).toContain('/logo.png')
  })
})