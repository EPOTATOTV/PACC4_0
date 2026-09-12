import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

/**
 * 对 api/client 的请求封装的回归测试：
 * 断言正确的 URL 前缀（/api/admin、/api/player）、HTTP 方法与 JSON 序列化。
 * 通过 mock 全局 fetch 完成，不真正发起网络请求。
 */
describe('api request wrapper', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  it('streamLive.list 请求管理端接口并返回 rows', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ rows: [{ id: '1', title: '决赛', bilibili_live_id: '123', live: true }], total: 1 }),
    })

    const d = await api.streamLive.list()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/admin/stream-live')
    expect((init as RequestInit).credentials).toBe('same-origin')
    expect(d.rows).toHaveLength(1)
    expect(d.total).toBe(1)
  })

  it('streamLive.setLive 发送 PUT 与 JSON body', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: '1', live: false }),
    })

    await api.streamLive.setLive('1', false)

    const [, init] = fetchMock.mock.calls[0]
    expect((init as RequestInit).method).toBe('PUT')
    const headers = (init as RequestInit).headers as Record<string, string>
    expect(headers['Content-Type']).toContain('application/json')
    expect((init as RequestInit).body).toBe(JSON.stringify({ live: false }))
  })

  it('player.liveStreams 请求玩家只读接口', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [{ id: '2', title: '半决赛', bilibili_live_id: '456', live: true }],
    })

    const d = await api.player.liveStreams()

    const [url] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/player/stream-live')
    expect(d).toHaveLength(1)
    expect(d[0].bilibili_live_id).toBe('456')
  })

  it('非 ok 响应抛出错误', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ error: 'unauthorized' }),
    })

    await expect(api.streamLive.list()).rejects.toBeTruthy()
  })
})