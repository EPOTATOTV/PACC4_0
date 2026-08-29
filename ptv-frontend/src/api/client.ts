import type {
  Account,
  Appeal,
  CheatRecord,
  CheatTypeCount,
  CompetitionOverview,
  DeviceRecord,
  Enrollment,
  InspectSession,
  IpCluster,
  MatchSession,
  MatchValidateResult,
  Peripheral,
  PlayerCurrentMatch,
  PlayerEnrollmentStatus,
  PlayerSummary,
  RedscreenAlert,
  Signature,
  StatsSummary,
  SupportTicket,
  SuspicionFlag,
  TournamentConfig,
  TournamentNotice,
  TournamentStage,
  PlayerRegisterInfo,
  TrendPoint,
} from '../types'

const ADMIN_KEY = 'pacc_admin_key'
const TOKEN_KEY = 'pacc_admin_token'
const PLAYER_TOKEN_KEY = 'pacc_player_token'

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

// ---- 玩家门户凭据 ----
export function storedPlayerToken(): string {
  return localStorage.getItem(PLAYER_TOKEN_KEY) ?? ''
}
export function setPlayerToken(token: string): void {
  localStorage.setItem(PLAYER_TOKEN_KEY, token)
}
export function clearPlayerAuth(): void {
  localStorage.removeItem(PLAYER_TOKEN_KEY)
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

// 玩家门户请求：携带 Bearer 令牌；会话失效时抛 401，由页面跳回登录
async function playerRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${storedPlayerToken()}`,
  }
  const res = await fetch(`/api/player${path}`, { ...init, headers })
  if (res.status === 401) {
    clearPlayerAuth()
    throw new Error('登录已过期，请重新登录')
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

export const api = {
  login(adminApiKey: string) {
    return fetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ admin_key: adminApiKey }),
    })
  },

  // ---- 玩家账号 / 门户 ----
  auth: {
    login(identity: string, password: string, remember = false) {
      return fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identity, password, remember }),
      })
    },
  },
  player: {
    summary: () => playerRequest<PlayerSummary>('/summary'),
    records: () => playerRequest<CheatRecord[]>('/records'),
    appeals: () => playerRequest<Appeal[]>('/appeals'),
    submitAppeal: (body: Record<string, string>) =>
      playerRequest<Appeal>('/appeals', { method: 'POST', body: JSON.stringify(body) }),
    tickets: () => playerRequest<SupportTicket[]>('/tickets'),
    submitTicket: (body: Record<string, string>) =>
      playerRequest<SupportTicket>('/tickets', { method: 'POST', body: JSON.stringify(body) }),
    devices: () => playerRequest<DeviceRecord[]>('/devices'),
    peripherals: () => playerRequest<Peripheral[]>('/peripherals'),
    myEnrollment: () => playerRequest<PlayerEnrollmentStatus>('/competition/enrollment'),
    myCurrentMatch: () => playerRequest<PlayerCurrentMatch>('/competition/matches/current'),
    validateMatch: (match_token: string) =>
      playerRequest<MatchValidateResult>('/competition/matches/validate', {
        method: 'POST',
        body: JSON.stringify({ match_token }),
      }),
    stages: () => playerRequest<TournamentStage[]>('/competition/stages'),
    notices: () => playerRequest<TournamentNotice[]>('/competition/notices'),
    registerInfo: (tournamentId: string) =>
      playerRequest<PlayerRegisterInfo>(`/competition/register?tournament_id=${encodeURIComponent(tournamentId)}`),
    submitRegister: (body: Record<string, string>) =>
      playerRequest<Enrollment>('/competition/register', { method: 'POST', body: JSON.stringify(body) }),
  },

  records: {
    list: (keyword = '') =>
      request<CheatRecord[]>(`/records?keyword=${encodeURIComponent(keyword)}`),
    revoke: (id: string, revoked: boolean) =>
      request<{ record_id: string; revoked: boolean }>(`/records/${id}/revoke`, {
        method: 'POST',
        body: JSON.stringify({ revoked, operator: 'admin' }),
      }),
  },

  competition: {
    overview: () => request<CompetitionOverview>('/competition/overview'),
    ipClusters: (minAccounts = 3) =>
      request<IpCluster[]>(`/competition/ip-clusters?min_accounts=${minAccounts}`),
    flags: (keyword = '') =>
      request<SuspicionFlag[]>(`/competition/flags?keyword=${encodeURIComponent(keyword)}`),
    review: (flagId: string, decision: string, comment = '') =>
      request<{ ok: boolean }>(`/competition/flags/${flagId}/review`, {
        method: 'POST',
        body: JSON.stringify({ decision, reviewer: 'admin', comment }),
      }),
    enrollments: () => request<Enrollment[]>('/competition/enrollments'),
    enroll: (body: Record<string, string>) =>
      request<Enrollment>('/competition/enrollments', { method: 'POST', body: JSON.stringify(body) }),
    approve: (id: string, approve: boolean, note = '') =>
      request<{ ok: boolean }>(`/competition/enrollments/${id}/approve`, {
        method: 'POST',
        body: JSON.stringify({ approve, operator: 'admin', note }),
      }),
    setTeam: (id: string, body: Record<string, string>) =>
      request<Enrollment>(`/competition/enrollments/${id}/team`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    setTeamBatch: (body: { enrollment_ids: string[]; team_name: string; team_color: string }) =>
      request<{ updated: number }>(`/competition/enrollments/team/batch`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    matches: () => request<MatchSession[]>('/competition/matches'),
    startMatch: (body: Record<string, string>) =>
      request<MatchSession>('/competition/matches', { method: 'POST', body: JSON.stringify(body) }),
    endMatch: (matchId: string) =>
      request<{ ok: boolean }>(`/competition/matches/${matchId}/end`, { method: 'POST' }),
    stages: (tournamentId: string) =>
      request<TournamentStage[]>(`/competition/stages?tournament_id=${encodeURIComponent(tournamentId)}`),
    addStage: (body: Record<string, string>) =>
      request<TournamentStage>('/competition/stages', { method: 'POST', body: JSON.stringify(body) }),
    updateStage: (stageId: string, body: Record<string, string>) =>
      request<{ ok: boolean }>(`/competition/stages/${stageId}`, { method: 'PUT', body: JSON.stringify(body) }),
    reorderStage: (tournamentId: string, from: number, to: number) =>
      request<{ ok: boolean }>('/competition/stages/reorder', {
        method: 'PATCH',
        body: JSON.stringify({ tournament_id: tournamentId, from, to }),
      }),
    deleteStage: (stageId: string) =>
      request<{ ok: boolean }>(`/competition/stages/${stageId}`, { method: 'DELETE' }),
    notices: (tournamentId: string) =>
      request<TournamentNotice[]>(`/competition/notices?tournament_id=${encodeURIComponent(tournamentId)}`),
    publishNotice: (body: Record<string, string | boolean>) =>
      request<TournamentNotice>('/competition/notices', { method: 'POST', body: JSON.stringify(body) }),
    deleteNotice: (noticeId: string) =>
      request<{ ok: boolean }>(`/competition/notices/${noticeId}`, { method: 'DELETE' }),
    config: (tournamentId: string) =>
      request<TournamentConfig>(`/competition/config?tournament_id=${encodeURIComponent(tournamentId)}`),
    updateConfig: (body: Record<string, string>) =>
      request<TournamentConfig>('/competition/config', { method: 'PUT', body: JSON.stringify(body) }),
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