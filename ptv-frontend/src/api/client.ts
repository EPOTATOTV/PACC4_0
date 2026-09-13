import type {
  Account,
  AdminLoginLog,
  Appeal,
  BiCheatTypeRow,
  BiLoginAudit,
  BiOverview,
  BiPlayerProfile,
  BiRedscreenHealth,
  BiTrend,
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
  ProtectionStatus,
  ProtectionStat,
  ProtectionResource,
  DetectionEvent,
  DetectorStatus,
  AiModelStatus,
  DetectionLogLine,
  RedScreenDetail,
  PlayerNotification,
  SecurityScore,
  LoginDevice,
  AppealDetail,
  TicketMessage,
  RealtimeOverview,
  RuntimeStat,
  RealtimeAlert,
  AlertRule,
  AlertEvent,
  AlertStats,
  AdminRole,
  AdminPlayerDetail,
  Broadcast,
  SignatureDiff,
  AbExperimentRow,
  AbSignificance,
  OpsHealth,
  OpsOverview,
  OpsCrashRow,
  OpsTelemetryRow,
  OpsConfigRow,
  SupportTicketItem,
  SupportFaqItem,
  SupportDashboard,
  SupportReplyResult,
  OpenApiKeyRow,
  OpenApiAuditRow,
  AuditOperationPage,
  AuditOverview,
  TenantRow,
  TenantAdminRow,
  AlertsListItem,
  DetectionAnalysis,
  V46Overview,
  ZeroDayAssessment,
  ThreatIngestResult,
  ThreatClusterResult,
  SignatureSeed,
  AnyRow,
  MapPool,
  MapEntry,
  MapBanPickSession,
  MapBanPickAction,
  BpStateDto,
  MapPoolStats,
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

/** 登录等关键交互请求的超时守卫：防止请求挂起时按钮永久停留在 loading 状态。 */
const LOGIN_TIMEOUT_MS = 15_000

async function timedFetch(input: RequestInfo | URL, init: RequestInit = {}, ms = LOGIN_TIMEOUT_MS): Promise<Response> {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), ms)
  try {
    return await fetch(input, { ...init, signal: ctl.signal })
  } finally {
    clearTimeout(timer)
  }
}

export const api = {
  login(adminApiKey: string) {
    return timedFetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ admin_key: adminApiKey }),
    })
  },

  // 管理后台会话：HttpOnly cookie 承载，/me 探测登录态，/logout 清理 cookie。
  // 登录态探测同样走超时守卫，避免后端无响应时加载页永久停留。
  adminSession: {
    me(): Promise<{ ok: boolean; role: string }> {
      return timedFetch('/api/admin/me', { credentials: 'same-origin' }).then((r) => {
        if (!r.ok) { const e = new Error('未登录') as Error & { status?: number }; e.status = r.status; throw e }
        return r.json()
      })
    },
    logout(): Promise<{ ok: boolean }> {
      return timedFetch('/api/admin/logout', { method: 'POST', credentials: 'same-origin' }).then((r) => r.json())
    },
  },

  // ---- 玩家账号 / 门户：所有认证 / 注册 / 找回流程统一加超时守卫 ----
  auth: {
    login(identity: string, password: string, remember = false) {
      return timedFetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identity, password, remember }),
      })
    },
    login2fa(pending: string, code: string, remember = false) {
      return timedFetch('/api/auth/login/2fa', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pending, code, remember }),
      })
    },
    register(body: Record<string, string>) {
      return timedFetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    forget(email: string) {
      return timedFetch('/api/auth/forget', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      })
    },
    reset(token: string, new_password: string) {
      return timedFetch('/api/auth/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, new_password }),
      })
    },
    sendCode(body: Record<string, string>) {
      return timedFetch('/api/auth/code/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    verifyCode(body: Record<string, string>) {
      return timedFetch('/api/auth/code/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
  },
  // ---- 管理后台登录（密钥 / 飞书）与登录日志 ----
  feishu: {
    url: () => timedFetch('/api/admin/feishu/oauth/url'),
    callback: (code: string) =>
      timedFetch('/api/admin/feishu/oauth/callback', {
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
    liveStreams: () => playerRequest<Broadcast[]>('/stream-live'),

    // ---- PACC 4.0 玩家端 P0：实时保护 / 监控 / 通知 / 安全 ----
    protection: {
      status: () => playerRequest<ProtectionStatus>('/protection/status'),
      resources: () => playerRequest<ProtectionResource[]>('/protection/resources'),
      stats: () => playerRequest<ProtectionStat>('/protection/stats'),
      recentEvents: (limit = 10) =>
        playerRequest<DetectionEvent[]>(`/protection/events?limit=${limit}`),
      pause: () => playerRequest<{ running: boolean }>('/protection/pause', { method: 'POST' }),
      resume: () => playerRequest<{ running: boolean }>('/protection/resume', { method: 'POST' }),
      scanNow: () => playerRequest<{ ok: boolean }>('/protection/scan', { method: 'POST' }),
    },
    monitor: {
      detectors: () => playerRequest<DetectorStatus[]>('/monitor/detectors'),
      ai: () => playerRequest<AiModelStatus>('/monitor/ai'),
      logs: (params: Record<string, string | number> = {}) => {
        const p = new URLSearchParams(params as Record<string, string>)
        return playerRequest<DetectionLogLine[]>(`/monitor/logs?${p.toString()}`)
      },
    },
    redscreenDetail: (id: string) => playerRequest<RedScreenDetail>(`/redscreen/${id}`),
    notifications: {
      list: (kind?: string) =>
        playerRequest<PlayerNotification[]>(`/notifications${kind ? `?kind=${kind}` : ''}`),
      read: (id: string) =>
        playerRequest<{ ok: boolean }>(`/notifications/${id}/read`, { method: 'POST' }),
      readAll: () => playerRequest<{ ok: boolean }>('/notifications/read-all', { method: 'POST' }),
      remove: (id: string) =>
        playerRequest<{ ok: boolean }>(`/notifications/${id}`, { method: 'DELETE' }),
      unreadCount: () => playerRequest<{ count: number }>('/notifications/unread-count'),
    },
    security: {
      score: () => playerRequest<SecurityScore>('/security/score'),
      devices: () => playerRequest<LoginDevice[]>('/security/devices'),
      logoutDevice: (deviceId: string) =>
        playerRequest<{ ok: boolean }>(`/security/devices/${deviceId}/logout`, { method: 'POST' }),
      changePassword: (body: Record<string, string>) =>
        playerRequest<{ ok: boolean }>('/security/password', { method: 'POST', body: JSON.stringify(body) }),
      totpSetup: () => playerRequest<{ secret: string; otpauth: string }>('/security/totp/setup'),
      totpEnable: (body: Record<string, string>) =>
        playerRequest<{ ok: boolean }>('/security/totp/enable', { method: 'POST', body: JSON.stringify(body) }),
      totpStatus: () =>
        playerRequest<{ enabled: boolean; recovery_ready: boolean }>('/security/totp/status'),
      totpRecovery: () =>
        playerRequest<{ recovery_codes: string[] }>('/security/totp/recovery', { method: 'POST' }),
      totpDisable: (code: string) =>
        playerRequest<{ ok: boolean }>('/security/totp/disable', {
          method: 'POST',
          body: JSON.stringify({ code }),
        }),
    },
    // ---- 地图 BP（Ban/Pick）：玩家端浏览与参与 ----
    maps: {
      pools: () => playerRequest<MapPool[]>('/maps/pools'),
      pool: (poolId: string) => playerRequest<MapPool>(`/maps/pools/${encodeURIComponent(poolId)}`),
      entries: (poolId: string) =>
        playerRequest<MapEntry[]>(`/maps/pools/${encodeURIComponent(poolId)}/entries`),
      bpCurrent: () => playerRequest<MapBanPickSession[]>('/maps/bp/current'),
      bpHistory: () => playerRequest<MapBanPickSession[]>('/maps/bp/history'),
      bpState: (bpId: string) => playerRequest<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}`),
      bpActions: (bpId: string) =>
        playerRequest<MapBanPickAction[]>(`/maps/bp/${encodeURIComponent(bpId)}/actions`),
      bpAction: (bpId: string, body: { action: string; map_id?: string; device_fingerprint?: string }) =>
        playerRequest<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}/action`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
    },
    appealDetail: (id: string) => playerRequest<AppealDetail>(`/appeals/${id}`),
    appealMessages: (id: string) => playerRequest<AppealDetail>(`/appeals/${id}`),
    ticketMessages: (id: string) => playerRequest<TicketMessage[]>(`/tickets/${id}/messages`),
    ticketReply: (id: string, reply: string) =>
      playerRequest<TicketMessage>(`/tickets/${id}/reply`, {
        method: 'POST',
        body: JSON.stringify({ reply }),
      }),
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

  // ---- v4.8 数据平台与 BI 报表 ----
  bi: {
    overview: (days = 30, startDate?: string, endDate?: string) => {
      const p = new URLSearchParams({ days: String(days) })
      if (startDate) p.set('startDate', startDate)
      if (endDate) p.set('endDate', endDate)
      return request<BiOverview>(`/bi/overview?${p.toString()}`)
    },
    detectionTrend: (days = 30) => request<BiTrend>(`/bi/detection-trend?days=${days}`),
    redscreenTrend: (days = 30) => request<BiTrend>(`/bi/redscreen-trend?days=${days}`),
    cheatTypes: () => request<{ items: BiCheatTypeRow[] }>('/bi/cheat-types'),
    redscreenHealth: () => request<BiRedscreenHealth>('/bi/redscreen-health'),
    playerProfile: () => request<BiPlayerProfile>('/bi/player-profile'),
    loginAudit: (days = 30) => request<BiLoginAudit>(`/bi/login-audit?days=${days}`),
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
    diff: (edition: string, afterVersion = 0) =>
      request<SignatureDiff>(`/signatures/diff?edition=${edition}&afterVersion=${afterVersion}&signed=true`),
    autoRollback: (falsePositiveRate: number) =>
      request<{ ok: boolean; rolled_back: number }>('/signatures/auto-rollback', {
        method: 'POST',
        body: JSON.stringify({ false_positive_rate: falsePositiveRate, edition: 'BEDROCK', operator: 'admin' }),
      }),
  },

  // ---- v4.7 检测算法 A/B 测试 ----
  ab: {
    list: () => request<AbExperimentRow[]>('/ab'),
    create: (body: Record<string, string | number>) =>
      request<AbExperimentRow>('/ab', { method: 'POST', body: JSON.stringify(body) }),
    significance: (id: string) => request<AbSignificance>(`/ab/${id}/significance`),
    breakdown: (id: string, body: Record<string, unknown>) =>
      request<Record<string, number>>(`/ab/${id}/breakdown`, { method: 'POST', body: JSON.stringify(body) }),
    finish: (id: string) => request<AbExperimentRow>(`/ab/finish/${id}`, { method: 'POST' }),
    publish: (id: string) => request<AbExperimentRow>(`/ab/publish/${id}`, { method: 'POST' }),
  },

  // ---- v4.7 自动化运维 ----
  ops: {
    health: () => request<OpsHealth>('/ops/health'),
    crashes: (limit = 50) => request<{ crashes: OpsCrashRow[] }>(`/ops/crashes?limit=${limit}`),
    telemetry: (limit = 50) => request<{ telemetry: OpsTelemetryRow[] }>(`/ops/telemetry?limit=${limit}`),
    overview: () => request<OpsOverview>('/ops/overview'),
    config: () => request<{ configs: OpsConfigRow[] }>('/ops/config'),
    saveConfig: (key: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/ops/config/${key}`, { method: 'PUT', body: JSON.stringify(body) }),
  },

  // ---- v4.7 客服工单 ----
  support: {
    tickets: (category?: string, status?: string) =>
      request<SupportTicketItem[]>(`/support/tickets${category ? `?category=${category}` : ''}${status ? `&status=${status}` : ''}`),
    createTicket: (body: Record<string, string>) =>
      request<SupportTicketItem>('/support/tickets', { method: 'POST', body: JSON.stringify(body) }),
    replyTicket: (id: string, reply: string, responder = 'admin') =>
      request<SupportReplyResult>(`/support/tickets/${id}/reply`, { method: 'POST', body: JSON.stringify({ reply, responder }) }),
    resolveTicket: (id: string) =>
      request<{ ok: boolean }>(`/support/tickets/${id}/resolve`, { method: 'POST', body: JSON.stringify({}) }),
    smartHitRate: () => request<{ hit_rate: number }>('/support/smart/hit-rate'),
    dashboard: () => request<SupportDashboard>('/support/dashboard'),
    faqList: () => request<SupportFaqItem[]>('/support/faq'),
    addFaq: (body: Record<string, string>) => request<SupportFaqItem>('/support/faq', { method: 'POST', body: JSON.stringify(body) }),
    deleteFaq: (id: string) => request<{ ok: boolean }>(`/support/faq/${id}`, { method: 'DELETE' }),
  },

  // ---- v4.8 开放 API 平台：API 密钥管理 + 调用审计 ----
  openApi: {
    keys: (tenant = 'platform') => request<OpenApiKeyRow[]>(`/api/keys?tenant=${tenant}`),
    createKey: (body: Record<string, string | number>) =>
      request<{ keyId: string; secret: string }>('/api/keys', { method: 'POST', body: JSON.stringify(body) }),
    rotateKey: (id: string) =>
      request<{ keyId: string; secret: string }>(`/api/keys/${id}/rotate`, { method: 'POST' }),
    toggleKey: (id: string, enabled: boolean) =>
      request<{ enabled: boolean }>(`/api/keys/${id}/toggle`, { method: 'POST', body: JSON.stringify({ enabled }) }),
    deleteKey: (id: string) =>
      request<{ deleted: boolean }>(`/api/keys/${id}`, { method: 'DELETE' }),
    setWebhook: (id: string, url: string, secret: string) =>
      request<{ updated: boolean }>(`/api/keys/${id}/webhook`, {
        method: 'POST',
        body: JSON.stringify({ url, secret }),
      }),
    audit: (keyId?: string, page = 0, size = 20) =>
      request<{ rows: OpenApiAuditRow[]; total: number }>(`/api/audit?page=${page}&size=${size}${keyId ? `&keyId=${encodeURIComponent(keyId)}` : ''}`),
  },

  // ---- v4.8 合规审计强化：管理员操作审计 ----
  audit: {
    operations: (params: Record<string, string> = {}) => {
      const p = new URLSearchParams(params)
      return request<AuditOperationPage>(`/audit/operations?${p.toString()}`)
    },
    trend: (days = 30) => request<{ days: string[]; counts: number[]; total?: number }>(`/audit/trend?days=${days}`),
    overview: (days = 30) => request<AuditOverview>(`/audit/overview?days=${days}`),
  },

  // ---- v4.8 多租户架构：租户管理 ----
  tenant: {
    list: (params: Record<string, string> = {}) => {
      const p = new URLSearchParams(params)
      return request<{ rows: TenantRow[]; total: number; page?: number }>(`/tenant/list?${p.toString()}`)
    },
    get: (id: string) => request<TenantRow>(`/tenant/${id}`),
    create: (body: Record<string, unknown>) =>
      request<TenantRow>('/tenant', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<TenantRow>(`/tenant/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) =>
      request<{ ok: boolean }>(`/tenant/${id}`, { method: 'DELETE' }),
    admins: (id: string) => request<TenantAdminRow[]>(`/tenant/${id}/admins`),
    addAdmin: (id: string, identity: string, role: string) =>
      request<TenantAdminRow>(`/tenant/${id}/admins`, { method: 'POST', body: JSON.stringify({ identity, role }) }),
    removeAdmin: (id: string, identity: string) =>
      request<{ ok: boolean }>(`/tenant/${id}/admins/${encodeURIComponent(identity)}`, { method: 'DELETE' }),
    setAdminEnabled: (id: string, identity: string, enabled: boolean) =>
      request<{ ok: boolean }>(`/tenant/${id}/admins/${encodeURIComponent(identity)}/enabled`, {
        method: 'PUT',
        body: JSON.stringify({ enabled }),
      }),
  },

  // ---- v5.0 赛事直播转播：管理端 CRUD ----
  streamLive: {
    list: () => request<{ rows: Broadcast[]; total: number }>('/stream-live'),
    create: (body: Record<string, unknown>) =>
      request<Broadcast>('/stream-live', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<Broadcast>(`/stream-live/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) =>
      request<{ ok: boolean }>(`/stream-live/${id}`, { method: 'DELETE' }),
    setLive: (id: string, live: boolean) =>
      request<Broadcast>(`/stream-live/${id}/live`, { method: 'PUT', body: JSON.stringify({ live }) }),
  },

  accounts: {
    list: (keyword?: string) =>
      request<Account[]>(`/accounts?keyword=${encodeURIComponent(keyword ?? '')}`),
  },

  // ---- v4.1 ----
  detection41: {
    analyze: (features: Record<string, number>) =>
      request<DetectionAnalysis>('/v41/analyze', { method: 'POST', body: JSON.stringify(features) }),
    demoCheat: () => request<DetectionAnalysis>('/v41/demo/cheat'),
    demoHuman: () => request<DetectionAnalysis>('/v41/demo/human'),
  },
  compliance: {
    selfCheck: () => request<AnyRow>('/compliance/selfcheck'),
    sla: () => request<AnyRow>('/compliance/sla'),
    branding: () => request<AnyRow>('/compliance/branding'),
    sbom: () => request<AnyRow>('/compliance/sbom'),
    supportSummary: () => request<{ pending_appeals: number; open_tickets: number; in_progress_tickets: number }>('/support/summary'),
    supportTickets: (status = 'open') => request<SupportTicket[]>(`/support/tickets?status=${status}`),
    supportAppeals: (status = 'pending') => request<Appeal[]>(`/support/appeals?status=${status}`),
    reviewAppeal: (id: string, body: Record<string, string>) =>
      request<{ ok: boolean }>(`/support/appeals/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    transitionTicket: (id: string, body: Record<string, string>) =>
      request<{ ok: boolean }>(`/support/tickets/${id}/transition`, { method: 'POST', body: JSON.stringify(body) }),
  },

  // ---- v5.0 系统管理 ----
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
    overview: () => request<V46Overview>('/v46/overview'),
    assessZeroDay: (body: Record<string, unknown>) =>
      request<ZeroDayAssessment>('/v46/zero-day/assess', { method: 'POST', body: JSON.stringify(body) }),
    reviewZeroDay: (id: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/v46/zero-day/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    reflowZeroDay: (id: string, reviewer?: string) =>
      request<{ sample_id?: string; family?: string }>(`/v46/zero-day/${id}/reflow`, { method: 'POST', body: JSON.stringify({ reviewer: reviewer ?? 'admin' }) }),
    ingestThreat: (body: Record<string, unknown>) =>
      request<ThreatIngestResult>('/v46/threat/ingest', { method: 'POST', body: JSON.stringify(body) }),
    reviewThreat: (id: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/v46/threat/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    analyzeThreat: (id: string) =>
      request<{ ok: boolean }>(`/v46/threat/analyze/${id}`, { method: 'POST' }),
    clusterThreat: (k?: number) =>
      request<ThreatClusterResult>('/v46/threat/cluster', { method: 'POST', body: JSON.stringify({ k: k ?? 3 }) }),
    threatClusters: () => request<ThreatClusterResult>('/v46/threat/clusters'),
    promoteThreat: (id: string) =>
      request<{ name: string; state: string }>(`/v46/threat/${id}/promote`, { method: 'POST', body: JSON.stringify({ operator: 'admin' }) }),
    signatures: () => request<SignatureSeed[]>('/v46/signatures'),
  },

  // ---- v4.7 威胁情报运营中台：家族谱系 / 主动威慑 / IOC 中心化 ----
  v47: {
    familyOverview: () => request<AnyRow>('/v47/family/overview'),
    familyGraph: () => request<AnyRow>('/v47/family/graph'),
    deterOverview: () => request<AnyRow>('/v47/deter/overview'),
    setDeter: (body: Record<string, unknown>) =>
      request<{ ok: boolean }>('/v47/deter/set', { method: 'POST', body: JSON.stringify(body) }),
    toggleDeter: (id: number, enabled: boolean) =>
      request<{ ok: boolean }>(`/v47/deter/${id}/toggle`, { method: 'POST', body: JSON.stringify({ enabled }) }),
    resolveDeter: (family: string) =>
      request<{ family: string; action: string; severity: number; protected: boolean }>('/v47/deter/resolve', { method: 'POST', body: JSON.stringify({ family }) }),
    iocOverview: () =>
      request<{ total: number; open: number; disarmed: number; subscribed: number; high_severity: number }>('/v47/ioc/overview'),
    iocList: (params: Record<string, string | number> = {}) => {
      const p = new URLSearchParams(params as Record<string, string>)
      return request<{ items: AnyRow[]; total: number }>(`/v47/ioc/list?${p.toString()}`)
    },
    importIoc: (sampleId: string) =>
      request<{ imported: number }>('/v47/ioc/import', { method: 'POST', body: JSON.stringify({ sampleId }) }),
    subscribeIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/subscribe`, { method: 'POST' }),
    disarmIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/disarm`, { method: 'POST' }),
    hitIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/hit`, { method: 'POST' }),
  },

  // ---- PACC 4.0 管理端 P0：实时监控 / 玩家详情 / 告警中心 / 角色权限 ----
  realtime: {
    overview: () => request<RealtimeOverview>('/realtime/overview'),
    runtime: () => request<RuntimeStat[]>('/realtime/runtime'),
    events: (limit = 30) => request<RealtimeAlert[]>(`/realtime/events?limit=${limit}`),
    alerts: (limit = 20) => request<RealtimeAlert[]>(`/realtime/alerts?limit=${limit}`),
  },
  playerDetail: (pteid: string) => request<AdminPlayerDetail>(`/players/${encodeURIComponent(pteid)}`),
  redscreenAdminDetail: (id: string) => request<RedScreenDetail>(`/redscreens/${id}`),
  alerts: {
    list: (level?: string, status?: string) => {
      const p = new URLSearchParams()
      if (level) p.set('level', level)
      if (status) p.set('status', status)
      return request<AlertsListItem[]>(`/alerts?${p.toString()}`)
    },
    rules: () => request<AlertRule[]>('/alerts/rules'),
    saveRule: (body: Record<string, unknown>) =>
      request<AlertRule>('/alerts/rules', { method: 'POST', body: JSON.stringify(body) }),
    toggleRule: (id: string, enabled: boolean) =>
      request<{ ok: boolean }>(`/alerts/rules/${id}`, { method: 'PUT', body: JSON.stringify({ enabled }) }),
    ack: (id: string) => request<{ ok: boolean }>(`/alerts/${id}/ack`, { method: 'POST' }),
    events: (status?: string, limit = 50) => {
      const p = new URLSearchParams()
      if (status) p.set('status', status)
      p.set('limit', String(limit))
      return request<AlertEvent[]>(`/alerts/events?${p.toString()}`)
    },
    ackEvent: (id: string) => request<AlertEvent>(`/alerts/events/${id}/ack`, { method: 'POST' }),
    resolveEvent: (id: string, note?: string) =>
      request<AlertEvent>(`/alerts/events/${id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ note: note ?? '' }),
      }),
    stats: () => request<AlertStats>('/alerts/stats'),
  },
  roles: {
    list: () => request<AdminRole[]>('/roles'),
    get: (id: string) => request<AdminRole>(`/roles/${id}`),
    save: (body: Record<string, unknown>) =>
      request<AdminRole>('/roles', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<AdminRole>(`/roles/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) => request<{ ok: boolean }>(`/roles/${id}`, { method: 'DELETE' }),
  },

  // ---- 地图 BP（Ban/Pick）：管理端地图池 / 地图条目 / BP 会话 ----
  maps: {
    pools: () => request<MapPool[]>('/maps/pools'),
    createPool: (body: Record<string, string>) =>
      request<MapPool>('/maps/pools', { method: 'POST', body: JSON.stringify(body) }),
    updatePool: (poolId: string, body: Record<string, string>) =>
      request<MapPool>(`/maps/pools/${encodeURIComponent(poolId)}`, { method: 'PUT', body: JSON.stringify(body) }),
    deletePool: (poolId: string) =>
      request<{ pool_id: string; ok: boolean }>(`/maps/pools/${encodeURIComponent(poolId)}`, { method: 'DELETE' }),

    entries: (poolId: string) =>
      request<MapEntry[]>(`/maps/pools/${encodeURIComponent(poolId)}/entries`),
    poolStats: (poolId: string) =>
      request<MapPoolStats>(`/maps/pools/${encodeURIComponent(poolId)}/stats`),
    createEntry: (poolId: string, body: Record<string, string>) =>
      request<MapEntry>(`/maps/pools/${encodeURIComponent(poolId)}/entries`, { method: 'POST', body: JSON.stringify(body) }),
    batchAddEntries: (poolId: string, body: { rows: Record<string, unknown>[]; created_by?: string }) =>
      request<{ pool_id: string; added: number }>(`/maps/pools/${encodeURIComponent(poolId)}/entries/batch`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    updateEntry: (mapId: string, body: Record<string, string | number | boolean>) =>
      request<MapEntry>(`/maps/entries/${encodeURIComponent(mapId)}`, { method: 'PUT', body: JSON.stringify(body) }),
    deleteEntry: (mapId: string) =>
      request<{ map_id: string; ok: boolean }>(`/maps/entries/${encodeURIComponent(mapId)}`, { method: 'DELETE' }),
    toggleEntry: (mapId: string) =>
      request<{ map_id: string; ok: boolean }>(`/maps/entries/${encodeURIComponent(mapId)}/toggle`, { method: 'POST' }),

    bpSessions: () => request<MapBanPickSession[]>('/maps/bp'),
    createBp: (body: Record<string, string>) =>
      request<MapBanPickSession>('/maps/bp', { method: 'POST', body: JSON.stringify(body) }),
    bpState: (bpId: string) => request<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}`),
    bpActions: (bpId: string) =>
      request<MapBanPickAction[]>(`/maps/bp/${encodeURIComponent(bpId)}/actions`),
    bpStart: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/start`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpPause: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/pause`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpResume: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/resume`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpReset: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/reset`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpCancel: (bpId: string, operator = 'admin', reason = '裁判取消') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ operator, reason }),
      }),
    bpComplete: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/complete`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpForceAction: (bpId: string, body: { action: string; map_id?: string; operator?: string }) =>
      request<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}/force`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  },
}