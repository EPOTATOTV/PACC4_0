import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import RuleEditor from './RuleEditor'

/**
 * 规则编辑器页的接线回归：规则名从源码里取、提交草稿打到 /api/admin/prl/versions、
 * 调试会话打到 /api/admin/prl/debug/session、性能面板读 /api/admin/prl/profiles/{ruleName}、
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

  function posts(path: string) {
    return fetchMock.mock.calls.filter(
      (call: unknown[]) => String(call[0]).endsWith(path) && (call[1] as RequestInit | undefined)?.method === 'POST',
    )
  }

  beforeEach(() => {
    fetchMock.mockReset()
    // 按路径给响应：/analyze 走 Linter 的节流分析，/versions 走版本面板的列表，
    // /profiles 走性能面板的采样，/debug 走调试面板的动作
    fetchMock.mockImplementation(async (url: string) => {
      if (url.includes('/analyze')) return ok({ diagnostics: [], engineVersion: '1.0.0' })
      if (url.includes('/versions')) return ok({ versions: [] })
      if (url.includes('/profiles/')) {
        return ok({
          ruleName: 'usb_device',
          ruleVersion: '1.0.0',
          executions: 42,
          avgMs: 0.5,
          p95Ms: 0.8,
          p99Ms: 1.2,
          maxMs: 2,
          memoryPeakKb: 4,
          hotspots: [],
          suggestions: [],
          window: '进程启动至今',
        })
      }
      if (url.includes('/debug/session')) {
        return ok({
          state: {
            sessionId: 'session-1',
            ruleName: 'usb_device',
            ruleVersion: '1.0.0',
            status: 'paused',
            currentLine: 19,
            variables: [],
            callStack: [{ id: 'frame-0', name: 'usb_device', line: 19 }],
            output: [{ seq: 1, line: 19, level: 'info', message: '会话已创建' }],
            isDemo: false,
          },
        })
      }
      return ok({})
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /**
   * 版本面板与性能面板挂载后各自拉一次数据。等它们落定再断言，否则这些请求会在测试结束后才
   * setState，触发一堆「not wrapped in act」告警，把真正的失败淹掉。
   */
  async function renderPage() {
    render(<RuleEditor />)
    await screen.findByText('没有版本记录')
    await screen.findByText('执行次数')
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

  it('性能面板按源码里的规则名与版本查采样', async () => {
    await renderPage()

    const url = String(fetchMock.mock.calls.find((call: unknown[]) => String(call[0]).includes('/profiles/'))?.[0])
    expect(url).toBe('/api/admin/prl/profiles/usb_device?version=1.0.0')
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('调试面板把当前源码与断点表交给后端开会话', async () => {
    await renderPage()
    await userEvent.click(screen.getByTitle('在第 15 行加断点'))
    await userEvent.click(screen.getByRole('button', { name: '开始会话' }))

    await waitFor(() => expect(posts('/debug/session')).toHaveLength(1))

    const [url, init] = posts('/debug/session')[0] as [string, RequestInit]
    expect(url).toBe('/api/admin/prl/debug/session')
    const body = JSON.parse(String(init.body)) as {
      ruleName: string
      source: string
      ruleVersion: string
      breakpoints: { id: string; line: number; enabled: boolean }[]
    }
    expect(body.ruleName).toBe('usb_device')
    expect(body.ruleVersion).toBe('1.0.0')
    expect(body.source).toContain('rule "usb_device"')
    expect(body.breakpoints).toEqual([{ id: 'usb_device:15', ruleName: 'usb_device', line: 15, enabled: true }])

    expect(await screen.findByText('已暂停')).toBeInTheDocument()
  })
})