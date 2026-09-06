import type {
  Account,
  AdminLoginLog,
  Appeal,
  CheatRecord,
  CheatTypeCount,
  CompetitionOverview,
  CounterMeasureEnvOverview,
  DeviceRecord,
  Enrollment,
  EnrollmentStats,
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
  SystemInfo,
  SystemConfig,
  SystemAdmins,
} from '../types'

// 管理后台凭据已迁至 HttpOnly 会话 cookie（pacc_admin）：JS 不再持有/读取密钥或令牌，
// 由浏览器同源自动附带；登录态统一由后端 /api/admin/me 探测判定。

// 玩家门户凭据已迁至 HttpOnly 会话 cookie：JS 不再持有/读取令牌，仅依赖浏览器自动携带。

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  const res = await fetch(`/api/admin${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

// 玩家门户请求：凭据由同源 HttpOnly cookie 自动携带；会话失效时抛 401，由页面跳回登录
async function playerRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  const res = await fetch(`/api/player${path}`, { ...init, headers, credentials: 'same-origin' })
  if (res.status === 401) {
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
      credentials: 'same-origin',
      body: JSON.stringify({ admin_key: adminApiKey }),
    })
  },

  // 管理后台会话：HttpOnly cookie 承载，/me 探测登录态，/logout 清理 cookie
  adminSession: {
    me(): Promise<{ ok: boolean; role: string }> {
      return fetch('/api/admin/me', { credentials: 'same-origin' }).then((r) => {
        if (!r.ok) { const e = new Error('未登录') as Error & { status?: number }; e.status = r.status; throw e }
        return r.json()
      })
    },
    logout(): Promise<{ ok: boolean }> {
      return fetch('/api/admin/logout', { method: 'POST', credentials: 'same-origin' }).then((r) => r.json())
    },
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
    register(body: Record<string, string>) {
      return fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    forget(email: string) {
      return fetch('/api/auth/forget', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      })
    },
    reset(token: string, new_password: string) {
      return fetch('/api/auth/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, new_password }),
      })
    },
    sendCode(body: Record<string, string>) {
      return fetch('/api/auth/code/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    verifyCode(body: Record<string, string>) {
      return fetch('/api/auth/code/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
  },
  // ---- 管理后台登录（密钥 / 飞书）与登录日志 ----
  feishu: {
    url: () => fetch('/api/admin/feishu/oauth/url'),
    callback: (code: string) =>
      fetch('/api/admin/feishu/oauth/callback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ code }),
      }),
  },
  adminLoginLogs: () => request<{ logs: AdminLoginLog[] }>('/login-logs'),
  player: {
    me: () => playerRequest<{ pteid: string }>('/me'),
    logout: () => fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }),
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
    enrollmentStats: () => request<EnrollmentStats>('/competition/enrollments/stats'),
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
    updateStage: (stageId: string, body: Record<string, unknown>) =>
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
    list: (edition: string, state?: string) =>
      request<Signature[]>(`/signatures?edition=${edition}${state ? `&state=${state}` : ''}`),
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

  // ---- v4.2 系统管理 ----
  system: {
    info: () => request<SystemInfo>('/system/info'),
    config: () => request<SystemConfig>('/system/config'),
    admins: () => request<SystemAdmins>('/system/admins'),
  },

  // ---- v4.5 二期 DMA/IOMMU 环境巡检 ----
  countermeasure: {
    environment: () => request<CounterMeasureEnvOverview>('/countermeasure/environment'),
  },

  // ---- v4.6 检测能力深化：零日 / 威胁情报 / 特征库扩充 / 主动学习 ----
  v46: {
    overview: () => request<any>('/v46/overview'),
    assessZeroDay: (body: Record<string, unknown>) =>
      request<any>('/v46/zero-day/assess', { method: 'POST', body: JSON.stringify(body) }),
    reviewZeroDay: (id: string, body: Record<string, unknown>) =>
      request<any>(`/v46/zero-day/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    reflowZeroDay: (id: string, reviewer?: string) =>
      request<any>(`/v46/zero-day/${id}/reflow`, { method: 'POST', body: JSON.stringify({ reviewer: reviewer ?? 'admin' }) }),
    ingestThreat: (body: Record<string, unknown>) =>
      request<any>('/v46/threat/ingest', { method: 'POST', body: JSON.stringify(body) }),
    reviewThreat: (id: string, body: Record<string, unknown>) =>
      request<any>(`/v46/threat/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    analyzeThreat: (id: string) =>
      request<any>(`/v46/threat/analyze/${id}`, { method: 'POST' }),
    clusterThreat: (k?: number) =>
      request<any>('/v46/threat/cluster', { method: 'POST', body: JSON.stringify({ k: k ?? 3 }) }),
    threatClusters: () => request<any>('/v46/threat/clusters'),
    promoteThreat: (id: string) =>
      request<any>(`/v46/threat/${id}/promote`, { method: 'POST', body: JSON.stringify({ operator: 'admin' }) }),
    signatures: () => request<any>('/v46/signatures'),
  },
}