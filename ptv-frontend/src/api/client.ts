import type {
  Account,
  CheatTypeCount,
  InspectSession,
  RedscreenAlert,
  Signature,
  StatsSummary,
  TrendPoint,
} from '../types'

const ADMIN_KEY = 'pacc_admin_key'
const TOKEN_KEY = 'pacc_admin_token'

export function storedAdminKey(): string {
  return localStorage.getItem(ADMIN_KEY) ?? ''
}

export function setAdminKey(key: string): void {
  localStorage.setItem(ADMIN_KEY, key)
}

export function clearAuth(): void {
  localStorage.removeItem(ADMIN_KEY)
  localStorage.removeItem(TOKEN_KEY)
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Admin-Key': storedAdminKey(),
  }
  const res = await fetch(`/api/admin${path}`, { ...init, headers })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

export const api = {
  login(adminKey: string) {
    return fetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ admin_key: adminKey }),
    })
  },

  stats: {
    summary: () => request<StatsSummary>('/stats/summary'),
    cheatTypes: () => request<CheatTypeCount[]>('/stats/cheat-types'),
    trend: (days = 7) => request<TrendPoint[]>(`/stats/redscreen-trend?days=${days}`),
  },

  redscreens: {
    list: (state = 'PENDING_INSPECT') =>
      request<RedscreenAlert[]>(`/redscreens?state=${state}`),
  },

  inspects: {
    pending: () => request<InspectSession[]>('/inspects/pending'),
    all: () => request<InspectSession[]>('/inspects/all'),
    start: (sessionId: string) =>
      request<InspectSession>(`/inspects/${sessionId}/start`, { method: 'POST',
        body: JSON.stringify({ operator: 'admin' }) }),
    conclude: (sessionId: string, conclusion: string, note: string) =>
      request<Account>(`/inspects/${sessionId}/conclude`, {
        method: 'POST',
        body: JSON.stringify({ conclusion, note }),
      }),
  },

  signatures: {
    list: (edition: string) =>
      request<Signature[]>(`/signatures?edition=${edition}`),
    add: (data: Record<string, string>) =>
      request<Signature>('/signatures', { method: 'POST', body: JSON.stringify(data) }),
    grayRelease: (edition: string, percent: number) =>
      request<{ ok: boolean }>('/signatures/gray-release', {
        method: 'POST',
        body: JSON.stringify({ edition, percent, operator: 'admin' }),
      }),
    rollback: (edition: string) =>
      request<{ ok: boolean; rolled_back: number }>('/signatures/rollback', {
        method: 'POST',
        body: JSON.stringify({ edition, operator: 'admin' }),
      }),
  },

  accounts: {
    list: (keyword?: string) =>
      request<Account[]>(`/accounts?keyword=${encodeURIComponent(keyword ?? '')}`),
  },

  // ---- v4.1 ----
  detection41: {
    analyze: (features: Record<string, number>) =>
      request<any>('/v41/analyze', { method: 'POST', body: JSON.stringify(features) }),
    demoCheat: () => request<any>('/v41/demo/cheat'),
    demoHuman: () => request<any>('/v41/demo/human'),
  },
  compliance: {
    selfCheck: () => request<any>('/compliance/selfcheck'),
    sla: () => request<any>('/compliance/sla'),
    branding: () => request<any>('/compliance/branding'),
    sbom: () => request<any>('/compliance/sbom'),
    supportSummary: () => request<any>('/support/summary'),
    supportTickets: (status = 'open') => request<any[]>(`/support/tickets?status=${status}`),
    supportAppeals: (status = 'pending') => request<any[]>(`/support/appeals?status=${status}`),
    reviewAppeal: (id: string, body: Record<string, string>) =>
      request<any>(`/support/appeals/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    transitionTicket: (id: string, body: Record<string, string>) =>
      request<any>(`/support/tickets/${id}/transition`, { method: 'POST', body: JSON.stringify(body) }),
  },
}