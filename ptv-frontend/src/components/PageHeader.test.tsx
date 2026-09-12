import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import PageHeader from './PageHeader'

describe('PageHeader', () => {
  it('渲染标题与描述', () => {
    render(<PageHeader title="数据大盘" description="实时汇总" />)
    expect(screen.getByText('数据大盘')).toBeInTheDocument()
    expect(screen.getByText('实时汇总')).toBeInTheDocument()
  })

  it('渲染右侧操作区', () => {
    render(<PageHeader title="检测" extra={<button>导出</button>} />)
    expect(screen.getByRole('button', { name: '导出' })).toBeInTheDocument()
  })

  it('无 error 时不渲染 Alert', () => {
    const { container } = render(<PageHeader title="监控" />)
    expect(container.querySelector('.ant-alert')).toBeNull()
  })

  it('error 存在时渲染 Alert 并可关闭', async () => {
    const onClose = vi.fn()
    render(<PageHeader title="监控" error="请求失败" onCloseError={onClose} />)
    expect(screen.getByText('请求失败')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('close'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})