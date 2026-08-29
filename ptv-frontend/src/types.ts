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