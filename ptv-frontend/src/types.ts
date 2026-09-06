// PACC PTV 管控后台共享类型

export interface Account {
  pteid: string
  email: string
  phone?: string
  reputation: number
  status: 'normal' | 'suspicious' | 'high_risk' | 'locked_inspect'
  totalRedscreen: number
  registeredAt: string
  lastRedScreenTime?: string
}

export interface RedscreenAlert {
  alertId: string
  level: number
  cheatType: string
  pteidMasked: string
  riskScore: number
  edition: string
  state: 'PENDING_INSPECT' | 'CONFIRMED' | 'FALSE_POSITIVE'
  inspectConclusion?: string
  broadcastOnline: number
  broadcastAck: number
  occurredAt: string
}

export interface InspectSession {
  sessionId: string
  pteid: string
  alertId: string
  operator?: string
  state: 'QUEUED' | 'ACTIVE' | 'DONE' | 'TIMEOUT' | 'CANCELLED'
  conclusion?: string
  auditLog?: string
  expiresAt?: string
  startedAt?: string
}

export interface Signature {
  id: string
  name: string
  pattern: string
  riskLevel: number
  edition: 'BEDROCK' | 'JAVA' | 'GENERIC'
  libraryVersion: string
  state: 'DRAFT' | 'PUBLISHED' | 'GRAY' | 'ROLLED_BACK'
  grayPercent?: number
  createdBy?: string
  createdAt: string
}

export interface StatsSummary {
  online_pteid: number
  detections_today: number
  redscreen_count: number
  pending_inspect: number
  active_inspect: number
  total_cheat_records: number
  total_accounts: number
  bedrock_players: number
  java_players: number
}

export interface CheatTypeCount {
  cheat_type: string
  count: number
}

export interface TrendPoint {
  date: string
  count: number
}

// ---- 赛事风控 / 参赛门禁 ----
export interface SuspicionFlag {
  flagId: string
  pteid: string
  kind: string
  detail: string
  weight: number
  evidenceSummary?: string
  prevChainHash?: string
  chainHash?: string
  status: 'OPEN' | 'REVIEWED' | 'ESB'
  createdAt: string
  reviewedAt?: string
  reviewer?: string
  reviewComment?: string
  duringMatch?: boolean
}

export interface Enrollment {
  enrollmentId: string
  tournamentId?: string
  pteid: string
  displayName?: string
  permittedDeviceFingerprint?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
  approvedAt?: string
  operator?: string
  note?: string
  teamName?: string
  teamColor?: string
}

export interface EnrollmentStats {
  total: number
  by_status: { TOTAL: number; APPROVED: number; PENDING: number; REJECTED: number }
  by_team: Record<string, number>
}

export interface CompetitionOverview {
  total_flags: number
  by_kind: Record<string, number>
}

export interface IpCluster {
  ip: string
  account_count: number
  pteids: string[]
}

export interface PlayerEnrollmentStatus {
  enrolled: boolean
  status?: string
  permitted: boolean
  canEnterMatch: boolean
  enrollment?: Enrollment | null
}

export interface MatchSession {
  matchId: string
  tournamentId?: string
  pteid: string
  deviceFingerprint?: string
  status: 'ACTIVE' | 'ENDED'
  startedAt: string
  expiresAt?: string
  endedAt?: string
  operator?: string
  lastSeenAt?: string
}

export interface MatchValidateResult {
  allowed: boolean
  reason: string
  message?: string
  match_id?: string
  expires_at?: string
}

export interface PlayerCurrentMatch {
  in_match: boolean
  match?: MatchSession
}

export interface TournamentStage {
  stageId: string
  tournamentId: string
  orderNo: number
  title: string
  kind: string
  status: 'PENDING' | 'ACTIVE' | 'DONE'
  startTime?: string
  endTime?: string
  resultNote?: string
  note?: string
  createdAt: string
}

export interface TournamentNotice {
  noticeId: string
  tournamentId: string
  title: string
  content?: string
  pinned: boolean
  operator?: string
  createdAt: string
  updatedAt?: string
}

export interface TournamentConfig {
  tournamentId: string
  title?: string
  tencentDocUrl?: string
  applyDeadline?: string
  allowRegister: boolean
  note?: string
}

export interface PlayerRegisterInfo {
  tournament_id: string
  title?: string
  tencent_doc_url?: string
  apply_deadline?: string
  allow_register: boolean
  submitted: number
  pending: number
  status: {
    enrolled: boolean
    status?: string
    permitted?: boolean
    canEnterMatch?: boolean
    enrollment?: Enrollment | null
  }
}

// ---- 玩家门户 / 记录 ----
export interface CheatRecord {
  recordId: string
  pteid: string
  alertId?: string
  cheatType: string
  level: number
  riskScore: number
  prevHash?: string
  recordHash?: string
  inspectConclusion?: string
  revoked: boolean
  occurredAt: string
}

export interface Appeal {
  appealId: string
  pteid: string
  alertId?: string
  reason: string
  description?: string
  status: 'pending' | 'approved' | 'rejected'
  reviewer?: string
  reviewComment?: string
  createdAt: string
  reviewedAt?: string
}

export interface SupportTicket {
  ticketId: string
  pteid: string
  channel: 'ticket' | 'email' | 'qq' | 'discord' | 'admin'
  subject: string
  body?: string
  status: 'open' | 'in_progress' | 'resolved' | 'closed'
  assignee?: string
  resolution?: string
  createdAt: string
  updatedAt?: string
}

export interface PlayerSummary {
  pteid: string
  record_count: number
  revoked_count: number
  pending_appeals: number
  open_tickets: number
}

export interface DeviceRecord {
  deviceId: string
  pteid: string
  deviceFingerprint?: string
  platform?: string
  deviceName?: string
  ip?: string
  firstLoginAt?: string
  lastLoginAt?: string
  active: boolean
}

export interface Peripheral {
  peripheralId: string
  pteid: string
  deviceFingerprint?: string
  kind: string
  vendor?: string
  model?: string
  connected: boolean
  firstSeenAt?: string
  lastSeenAt?: string
}

/** 管理后台登录审计日志。 */
export interface AdminLoginLog {
  id: number
  identity: string
  method: 'key' | 'feishu'
  role?: string | null
  result: 'success' | 'fail'
  ip: string
  created_at: string
}

// ---- v4.2 系统管理 ----
export interface SystemInfo {
  app: string
  version: string
  java_version: string
  active_profiles: string[]
  uptime_ms: number
  database: string
  host_uptime: string
}

export interface SystemConfig {
  detection: {
    redscreen_threshold: number
    severe_threshold: number
    suspicious_low: number
    cooldown_minutes: number
  }
  rules: { enabled: boolean; max_bonus: number }
  ai: { enabled: boolean }
  feishu: { enabled: boolean }
}

export interface AdminIdentity {
  identity: string
  method: 'key' | 'feishu' | 'none'
  role: string
  enabled: boolean
}

export interface SystemAdmins {
  accounts: AdminIdentity[]
  recent_login_events: number
  note: string
}

// ---- v4.5 二期 DMA/IOMMU 环境巡检 ----
export interface DmaRiskEvent {
  id: string
  pteid: string
  iommuEnabled: boolean
  acpiDmacIntegrity: boolean
  kernelDebuggerDetected: boolean
  pcieSuspicious: boolean
  memoryReadAlert: boolean
  antidebugFindings?: string
  score: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  findings?: string
  createdAt: string
}

export interface CounterMeasureEnvOverview {
  thresholds: { environment_suspect: number; environment_high: number }
  recent: DmaRiskEvent[]
  high_risk_accounts: { pteid: string; count: number; high_count: number; max_score: number }[]
  by_pteid: { pteid: string; count: number; high_count: number; max_score: number }[]
}

// ---- v4.8 数据平台与 BI 报表 ----
export interface BiTrend {
  days: string[]
  counts: number[]
  total: number
}

export interface BiCheatTypeRow {
  cheat_type: string
  count: number
}

export interface BiRedscreenHealth {
  pending: number
  confirmed: number
  false_positive: number
  total: number
  false_positive_rate: number
}

export interface BiCheatRecordHealth {
  persisted: number
  revoked: number
  total: number
}

export interface BiPlayerProfile {
  total: number
  reputation: Record<string, number>
  status: Record<string, number>
}

export interface BiAppealFunnel {
  by_stage: Record<string, Record<string, number>>
  total: number
  approved: number
  rejected: number
}

export interface BiLoginAudit {
  days: string[]
  success: number[]
  fail: number[]
}

export interface BiOverview {
  detection_trend: BiTrend
  redscreen_trend: BiTrend
  cheat_types: { items: BiCheatTypeRow[] }
  redscreen_health: BiRedscreenHealth
  cheat_record_health: BiCheatRecordHealth
  player_profile: BiPlayerProfile
  edition_split: { bedrock: number; java: number }
  appeal_funnel: BiAppealFunnel
  login_audit: BiLoginAudit
}