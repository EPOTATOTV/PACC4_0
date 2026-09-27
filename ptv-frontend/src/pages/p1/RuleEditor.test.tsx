import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import RuleEditor from './RuleEditor'

/**
 * 规则编辑器页的接线回归：规则名从源码里取、提交草稿打到 /api/admin/prl/versions、
 * 输入不合法时一个请求都不发。
 *
 * 只测本页的接线，组件本身（@potatotv/prl-editor）的单元测试不在这个仓库里。
 */
describe('RuleEditor 页面接线', () => {
  const fetchMock = vi.fn()

  function ok(body: unknown) {
    return { ok: true, status: 200, json: async () => body, text: async () => '' }
  }

  /** 提交草稿产生的 POST /versions，与列表的 GET /versions 区分开。 */
  function draftPosts() {
    return fetchMock.mock.calls.filter(
      (call: unknown[]) => String(call[0]).endsWith('/versions') && (call[1] as RequestInit | undefined)?.method === 'POST',
    )
  }

  beforeEach(() => {
    fetchMock.mockReset()
    // 按路径给响应：/analyze 走 Linter 的节流分析，/versions 走版本面板的列表
    fetchMock.mockImplementation(async (url: string) => {
      if (url.includes('/analyze')) return ok({ diagnostics: [], engineVersion: '1.0.0' })
      if (url.includes('/versions')) return ok({ versions: [] })
      return ok({})
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /**
   * 版本面板挂载后自己拉一次列表。等它落定再断言，否则这次请求会在测试结束后才 setState，
   * 触发一堆「not wrapped in act」告警，把真正的失败淹掉。
   */
  async function renderPage() {
    render(<RuleEditor />)
    await screen.findByText('没有版本记录')
  }

  it('提示条显示源码里声明的规则名', async () => {
    await renderPage()
    expect(screen.getByText('usb_device')).toBeInTheDocument()
  })

  it('提交草稿打到管理端路径，body 带规则名、版本号与源码', async () => {
    await renderPage()
    const versionInput = screen.getByPlaceholderText('1.0.0')
    await userEvent.clear(versionInput)
    await userEvent.type(versionInput, '2.1.0')
    await userEvent.type(screen.getByPlaceholderText('这次改了什么、为什么改'), '调高阈值')
    await userEvent.click(screen.getByRole('button', { name: '提交草稿' }))

    await waitFor(() => expect(draftPosts()).toHaveLength(1))

    const [url, init] = draftPosts()[0] as [string, RequestInit]
    expect(url).toBe('/api/admin/prl/versions')
    expect((init as RequestInit & { credentials?: string }).credentials).toBe('same-origin')
    const body = JSON.parse(String(init.body)) as Record<string, string>
    expect(body.ruleName).toBe('usb_device')
    expect(body.version).toBe('2.1.0')
    expect(body.note).toBe('调高阈值')
    // 作者留空，由后端取当前登录管理员，不塞假名字
    expect(body.author).toBe('')
    expect(body.source).toContain('rule "usb_device"')

    expect(await screen.findByText(/已提交 usb_device v2\.1\.0 的草稿/)).toBeInTheDocument()
    // 提交成功后版本面板会重新挂载再拉一次列表，等它落定
    await screen.findByText('没有版本记录')
  })

  it('版本号不是 x.y.z 时只报错、不发请求', async () => {
    await renderPage()
    const versionInput = screen.getByPlaceholderText('1.0.0')
    await userEvent.clear(versionInput)
    await userEvent.type(versionInput, '1.0')
    await userEvent.click(screen.getByRole('button', { name: '提交草稿' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('x.y.z'))
    expect(draftPosts()).toHaveLength(0)
  })

  it('源码里没有 rule 声明时拒绝提交', async () => {
    await renderPage()
    await userEvent.clear(screen.getByLabelText('PRL 源码'))
    await userEvent.click(screen.getByRole('button', { name: '提交草稿' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('不知道该提交给哪条规则'))
    expect(draftPosts()).toHaveLength(0)
    expect(screen.getByText('未命名')).toBeInTheDocument()
  })
})