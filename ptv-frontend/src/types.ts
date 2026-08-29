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