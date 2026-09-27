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
  /** BP 最终选图（JSON 数组字符串，对局前地图选择结果）。 */
  selectedMaps?: string
  /** 关联的 BP 会话 ID（若本场对局走了地图 BP 流程）。 */
  bpSessionId?: string
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

// ---- v5.0 系统管理 ----
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

// ===================== PACC 4.0 前端升级 · 玩家端 =====================

/** 实时保护仪表盘 - 保护状态大卡片 */
export interface ProtectionStatus {
  running: boolean
  state: 'RUNNING' | 'PAUSED' | 'ERROR'
  mode: string
  scannable_regions: number
  scanned_regions: number
  uptime_sec: number
  detection_count: number
  redscreen_count: number
  last_event_type?: string
}

/** 实时保护仪表盘 - 实时资源占用 */
export interface ProtectionResource {
  cpu: number
  memory: number
  network: number
  ts: number
}

/** 实时保护仪表盘 - 今日检测统计 */
export interface ProtectionStat {
  detections_today: number
  high_risk_today: number
  redscreen_today: number
  false_positive_today: number
}

/** 最近检测事件（实时事件流） */
export interface DetectionEvent {
  id: string
  type: string
  riskScore: number
  timestamp: string
  player?: string
}

/** 检测实时监控 - 检测器状态 */
export interface DetectorStatus {
  id: string
  name: string
  kind: 'violent' | 'stealth'
  state: 'OK' | 'DEGRADED' | 'ERROR' | 'OFF'
  scanCount: number
  hitCount: number
}

/** 检测实时监控 - AI 模型推理状态 */
export interface AiModelStatus {
  modelVersion: string
  latencyMs: number
  predictions: { label: string; count: number }[]
}

/** 检测实时监控 - 实时日志条目 */
export interface DetectionLogLine {
  ts: number
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
  origin: string
  message: string
}

/** 红屏事件详情 */
export interface RedScreenDetail {
  eventId: string
  triggeredAt: string
  level: number
  state: string
  cheatType: string
  riskScore: number
  hitDetectors: { name: string; matched: boolean; risk: number }[]
  evidence?: {
    memory?: string
    behavior?: string
    processSnapshot?: string[]
    deviceInfo?: string
  }
  timeline: { at: string; action: string; note?: string }[]
  player?: { pteid: string; reputation: number; device: string }
}

/** 通知中心条目 */
export interface PlayerNotification {
  id: string
  kind: 'redscreen' | 'appeal' | 'ticket' | 'system' | 'activity' | 'reputation' | 'device' | 'match'
  title: string
  body?: string
  read: boolean
  createdAt: string
}

/** 账号安全中心 - 安全评分 */
export interface SecurityScore {
  score: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  suggestions: string[]
}

/** 账号安全中心 - 登录设备 */
export interface LoginDevice {
  deviceId: string
  name: string
  platform?: string
  ip?: string
  online: boolean
  suspicious: boolean
  lastActiveAt: string
}

/** 申诉详情 */
export interface AppealDetail extends Appeal {
  timeline: { at: string; action: string; note?: string }[]
  messages: { from: 'player' | 'reviewer'; content: string; at: string }[]
}

/** 工单对话消息 */
export interface TicketMessage {
  id: string
  reply: string
  responder?: string
  createdAt: string
}

// ===================== PACC 4.0 前端升级 · 管理端 =====================

/** 实时监控大屏 - 系统概览 */
export interface RealtimeOverview {
  online: number
  detections: number
  redscreenToday: number
  pendingInspect: number
  activeInspect: number
  avgRisk: number
}

/** 实时监控大屏 - 运行状态项 */
export interface RuntimeStat {
  key: string
  label: string
  value: number
  unit?: string
  status?: 'ok' | 'warn' | 'err'
}

/** 实时监控大屏 - 告警条目 */
export interface RealtimeAlert {
  id: string
  level: number
  type: string
  message: string
  player?: string
  time: string
}

/** 告警中心 - 告警规则 */
export interface AlertRule {
  id: string
  name: string
  scope: string
  condition: string
  threshold: number
  cooldownMin: number
  enabled: boolean
  channels: string[]
}

/** 告警中心 - 规则引擎触发的事件（FIRING / ACKNOWLEDGED / RESOLVED） */
export interface AlertEvent {
  id: string
  ruleId?: string
  ruleName: string
  severity: number
  metric?: string
  conditionValue?: string
  threshold?: number
  actualValue?: number
  status: 'FIRING' | 'ACKNOWLEDGED' | 'RESOLVED'
  firedAt: string
  acknowledgedAt?: string
  acknowledgedBy?: string
  resolvedAt?: string
  resolutionNote?: string
}

/** 告警中心 - 规则引擎统计 */
export interface AlertStats {
  firing: number
  acknowledged: number
  resolved: number
  total: number
}

/** 角色与权限 - 权限项 */
export interface RolePermission {
  module: string
  moduleLabel: string
  actions: { key: string; label: string; granted: boolean }[]
}

/** 角色与权限 - 角色 */
export interface AdminRole {
  id: string
  name: string
  key: string
  description?: string
  builtin: boolean
  memberCount: number
  permissions: RolePermission[]
}

/** 玩家详情（管理端视角） */
export interface AdminPlayerDetail {
  pteid: string
  email?: string
  reputation: number
  status: string
  registeredAt: string
  totalRedscreen: number
  lastActiveAt?: string
  devices: DeviceRecord[]
  appeals: Appeal[]
  inspects: InspectSession[]
  records: CheatRecord[]
}

/** 赛事直播转播配置（管理端维护，玩家端只读展示 live=true 项）。 */
export interface Broadcast {
  id: string
  title: string
  bilibili_live_id: string
  cover_url?: string | null
  description?: string | null
  platform: string
  live: boolean
  sort: number
  created_at: string
  updated_at?: string | null
}

/** 松散记录：值为服务端动态字段，避免显式 any。 */
export type AnyRow = Record<string, unknown>

// ===================== v4.7 检测算法 A/B 测试 =====================
/** A/B 实验实体（后端 camelCase JSON 字段）。 */
export interface AbExperimentRow {
  id: string
  name: string
  description?: string
  dimension: string
  variantA: string
  variantB: string
  targetPercent: number
  status: string
  metricsCtExposure: number
  metricsCtDetect: number
  metricsCtFalsePositive: number
  startedAt?: string
  endedAt?: string
  winner?: string
  createdAt: string
}

/** A/B 显著性结果（后端 snake_case）。 */
export interface AbSignificance {
  ctr_a: number
  ctr_b: number
  lift: number
  p_value: number
  significant: boolean
}

// ===================== v4.7 自动化运维 =====================
export interface OpsHealth {
  status?: string
  db?: string
  db_error?: string
  memory?: { used_mb?: number; max_mb?: number }
  uptime_seconds?: number
}

export interface OpsOverview {
  total_crashes?: number
  crash_count_last_24h?: number
  avg_cpu?: number
  avg_mem_mb?: number
  sample_telemetry_count?: number
}

export interface OpsCrashRow {
  id: string
  platform?: string
  clientVersion?: string
  os?: string
  arch?: string
  controller?: string
  createdAt?: string
}

export interface OpsTelemetryRow {
  id: string
  cpuPercent?: number
  memMb?: number
  detectionLatencyMs?: number
  fpsImpactPercent?: number
  pteid?: string
  createdAt?: string
}

export interface OpsConfigRow {
  id: string
  category?: string
  intValue?: number | null
  doubleValue?: number | null
  boolValue?: boolean | null
  updatedBy?: string | null
  updatedAt?: string
}

// ===================== v4.7 客服工单 =====================
export interface SupportTicketItem {
  id: string
  pteid?: string
  category?: string
  title?: string
  description?: string
  status?: string
  priority?: string
  assignee?: string
  firstReplyAt?: string
  resolvedAt?: string
  createdAt?: string
}

export interface SupportFaqItem {
  id: string
  question?: string
  answer?: string
  keywords?: string
  createdAt?: string
}

export interface SupportDashboard {
  open_count: number
  by_category: Record<string, number>
  by_priority: Record<string, number>
  avg_first_reply_seconds: number
  smart_hit_rate: number
}

export interface SupportReplyResult {
  sla_info: {
    priority: string
    first_reply_seconds: number
    sla_budget_seconds: number
    in_sla: boolean
  }
}

// ===================== v4.8 开放 API 平台 =====================
export interface OpenApiKeyRow {
  keyId: string
  name: string
  tenantId: string
  plan: string
  scopes: string
  categories?: string
  ipWhitelist?: string
  rateLimitPerHour: number
  webhookUrl?: string
  enabled: boolean
  createdAt: string
  lastUsedAt?: string
}

export interface OpenApiAuditRow {
  id: string
  createdAt: string
  method: string
  path: string
  apiKeyId?: string
  ip?: string
  statusCode: number
  latencyMs?: number
}

// ===================== v4.8 合规审计：管理员操作审计 =====================
export interface AuditOperationPage {
  rows: AnyRow[]
  total: number
  page: number
}

export interface AuditOverview {
  total: number
  error_rate: number
  error_count: number
  by_status: Record<string, number>
  top_actions: { action: string; count: number }[]
}

// ===================== v4.8 多租户架构：租户管理 =====================
export type TenantRow = Record<string, unknown>

export interface TenantAdminRow {
  id: number
  tenantId: string
  adminIdentity: string
  role: string
  enabled: boolean
}

// ===================== v4.7 特征库增量 diff =====================
export interface SignatureDiff {
  count: number
  digest: string
  signature?: string
  library_version?: string
  changes?: Signature[]
}

// ===================== 告警中心列表项 =====================
export interface AlertsListItem {
  id: string
  type: string
  level: number
  status: 'open' | 'acknowledged' | 'resolved'
  message: string
  player?: string
  time: string
}

// ===================== v4.1 检测分析引擎 =====================
export interface DetectionVerdict {
  cheatType?: string
  displayName?: string
  name?: string
  detected: boolean
  confidence: number
  hits: { layer: number; layerName: string; signal: string; weight: number; type?: string }[]
  summary: string
}

export interface DetectionAnalysis {
  edition: string
  feature_dims: number
  brute_force: DetectionVerdict[]
  stealth: DetectionVerdict[]
  ai_behavior?: { cheat_prob: number; level: string; n_features: number; model?: string }
  ai_human_likeness?: { human_likeness: number; verdict: string }
}

// ===================== v4.6 检测能力深化：零日 / 威胁情报 / 特征库扩充 / 主动学习 =====================
export interface ZeroDayFinding {
  id?: string
  pteid?: string
  confidenceTier?: string
  compositeScore?: number
  status?: string
  createdAt?: string
}

export interface ThreatSample {
  id?: string
  pteid?: string
  family?: string
  familyLabel?: string
  autoAnalysis?: string
  generatedRule?: string
  status?: string
  confirmed?: boolean | string
  md5?: string
  sha1?: string
  edition?: string
  createdAt?: string
}

export interface SignatureSeed {
  name: string
  pattern: string
  riskLevel: number
  edition: string
}

export interface ThreatIngestMatch {
  name: string
  riskLevel: number
}

export interface ThreatIngestResult {
  sample_id: string
  family?: string
  generated_rule?: string
  matches?: ThreatIngestMatch[]
}

export interface ThreatClusterResult {
  analyzed: number
  clusters?: AnyRow
  distribution?: Record<string, number>
}

export interface ZeroDayAssessment {
  confidence_tier: string
  composite: number
  iso_score?: number
  recon_error?: number
  baseline_deviation?: number
  finding_id: string
}

export interface ThreatAnalysisReport {
  tier?: string
  severity?: string
  type?: string
  summary?: string
  matched_seeds?: string[]
  indicators?: string[]
  suggestion?: string
}

export interface V46Overview {
  zero_day?: { open?: number; recent?: ZeroDayFinding[] }
  active_learning?: { zero_day_queue?: ZeroDayFinding[] }
  threat_intel?: { new_count?: number; recent?: ThreatSample[] }
  signature_expansion?: { seed_count?: number; seeds?: SignatureSeed[] }
}

// ===================== 地图 BP（Ban/Pick） =====================
export type MapPoolFormat = 'BO1' | 'BO3' | 'BO5'

export interface MapPool {
  poolId: string
  tournamentId?: string
  name: string
  gameMode?: string
  edition?: string
  description?: string
  active: boolean
  mapCount: number
  createdBy?: string
  createdAt: string
  updatedAt?: string
}

export interface MapEntry {
  mapId: string
  poolId: string
  name: string
  nameEn?: string
  mapType?: string
  author?: string
  version?: string
  difficulty?: string
  thumbnailUrl?: string
  previewImages?: string
  description?: string
  downloadUrl?: string
  banCount: number
  pickCount: number
  winRateBlue: number
  winRateRed: number
  active: boolean
  orderNo: number
  createdBy?: string
  createdAt: string
  updatedAt?: string
}

export type BpStatus = 'PENDING' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED'

export interface MapBanPickSession {
  bpSessionId: string
  tournamentId?: string
  matchId?: string
  stageId?: string
  poolId: string
  format: MapPoolFormat
  status: BpStatus
  blueEnrollmentId?: string
  redEnrollmentId?: string
  blueTeamName?: string
  redTeamName?: string
  turnIndex: number
  currentTurn?: string
  currentRound: number
  totalRounds: number
  turnTimeoutSeconds: number
  startTime?: string
  endTime?: string
  currentTurnDeadline?: string
  selectedMaps?: string
  bannedMaps?: string
  referee?: string
  createdBy?: string
  cancelReason?: string
  createdAt: string
  updatedAt?: string
}

export type BpSide = 'BLUE' | 'RED'
export type BpActionType = 'BAN' | 'PICK'

export interface MapBanPickAction {
  actionId: string
  bpSessionId: string
  roundNo: number
  team: BpSide
  actionType: BpActionType
  mapId?: string
  mapName?: string
  operatorPteid?: string
  operatorDeviceFp?: string
  clientIp?: string
  responseTimeMs?: number
  timeout: boolean
  createdAt: string
}

/** selected_maps / banned_maps JSON 数组元素。 */
export interface BpListedMap {
  map_id: string
  map_name: string
  side: BpSide
  round: number
}

/** BP 实时态（/state 返回的扁平 DTO）。 */
export interface BpStateDto {
  bp_session_id: string
  tournament_id?: string
  match_id?: string
  format: MapPoolFormat
  status: BpStatus
  pool_id: string
  blue_team_name?: string
  red_team_name?: string
  turn_index: number
  current_turn?: string
          current_round: number
          total_rounds: number
          turn_timeout_seconds: number
          turn_deadline?: string
          can_act_for?: BpSide
          /** 玩家端专用：当前登录选手所在阵营（管理端 DTO 无此字段） */
          my_side?: BpSide
  start_time?: string
  end_time?: string
  cancel_reason?: string
  selected_maps: BpListedMap[]
  banned_maps: BpListedMap[]
  actions: MapBanPickAction[]
}

export interface MapPoolStats {
  pool_id: string
  total: number
  active: number
  ban_total: number
  pick_total: number
  by_type: Record<string, number>
}

// ---- P1（v5.0）：版本发布 / 黑白名单 / 数据导出 / 反作弊效果 / 检测配置 ----

export interface ReleaseInfoRow {
  id: string
  platform: string
  channel: string
  version: string
  build_no: number
  notes?: string
  download_url?: string
  sha256?: string
  min_app_version?: string
  manual_enabled: boolean
  forced_enabled: boolean
  crash_rate_pct: number
  status: string
  published_at?: string
  created_by?: string
  created_at: string
}

export interface ListEntryRow {
  id: string
  list_type: string
  entry_type: string
  value: string
  reason: string
  status: string
  created_by?: string
  created_at: string
  expires_at?: string
}

export interface ExportTaskRow {
  id: string
  subject: string
  filters: string
  status: string
  requestedBy: string
  format: string
  filePath: string
  downloadKey?: string
  rowCount: number
  errorMsg?: string
  requestedAt: string
  completedAt?: string
  expiresAt?: string
}

export interface EffectivenessSummary {
  total_cheat_records: number
  confirmed_cheats: number
  false_positives: number
  appeals_approved: number
  total_redscreens: number
  total_suspicion_flags: number
  precision_pct: number
  false_positive_pct: number
  healthy: boolean
}

export interface CheatTypeDistRow {
  cheat_type: string
  count: number
}

export interface DetectorConfigRow {
  detector_key: string
  name: string
  enabled: boolean
  meta: string
  updated_by?: string
  updated_at: string
  configured: boolean
}

export interface ReputationTrendPoint {
  date: string
  score: number
}

export interface ReputationSummary {
  score: number
  tier: string
  equities: string[]
  trend: ReputationTrendPoint[]
}

export interface ExportSubmitResult {
  task_id: string
  status: string
}

// ===================== v5.0 动效配置中心与下载站 =====================
export interface EffectConfigDto {
  id: string
  motion_level: string
  effects_json: string
  redscreen_template: string
  redscreen_json: string
  updated_by?: string
  updated_at: string
}

export interface DlRelease {
  id: string
  platform: string
  artifact: string
  version: string
  fileUrl: string
  sha256: string
  sizeBytes: number
  enabled: boolean
  updatedAt: string
}

export interface DlStats {
  days: number
  /** 近 N 天各平台累计下载次数 */
  platform: Record<string, number>
  /** 序列日期轴（升序，含无数据的日期） */
  dates: string[]
  /** 每个平台一条等长序列，data[i] 对应 dates[i] */
  series: { platform: string; data: number[] }[]
}

/** 动效配置变更记录（t_effect_config_audit） */
export interface EffectConfigAuditRow {
  id: number
  changedBy: string
  motionLevel: string
  redscreenTemplate: string
  summary: string
  createdAt: number
}

// ===================== v5.2 智能检测运营（管理端补齐） =====================
// 字段与后端视图一致使用 snake_case；模型状态/类型为后端常量（draft|gray|active|rollback、XGBOOST|AUTOENCODER）。

/** 模型版本（/api/admin/v52/model/versions）。 */
export interface V52ModelVersion {
  id: string
  model_type: string
  version: string
  file_url?: string
  sha256?: string
  accuracy: number
  false_positive_rate: number
  recall?: number
  training_samples: number
  status: string
  gray_percent: number
  created_at: string
  published_at: string
  /** 仅详情/生效版本接口返回（列表接口不含签名） */
  signature?: string
}

/** 手动训练结果（/api/admin/v52/model/train）。未达门禁时 trained=false 并给出 reason。 */
export interface V52TrainResult {
  trained: boolean
  reason?: string
  operator?: string
  window_days?: number
  samples?: number
  feature_dim?: number
  positives?: number
  negatives?: number
  split?: string
  train_samples?: number
  eval_samples?: number
  models?: AnyRow[]
}

/** 行为画像视图（/api/admin/v52/profile/{pteid}）。无画像行时 exists=false，仅返回基础字段。 */
export interface V52BehaviorProfile {
  pteid: string
  exists: boolean
  mean_cps?: number
  cps_std?: number
  mean_aim_smoothness?: number
  aim_smoothness_std?: number
  mean_speed?: number
  speed_std?: number
  total_sessions?: number
  total_detections?: number
  false_positives?: number
  reputation_score?: number
  reputation_level?: string
  profile_version?: number
  sample_count: number
  stability: number
  observed_seconds: number
  adaptive_multiplier: number
  fingerprint_mutation: boolean
  first_seen_at?: string
  updated_at?: string
}

/** 风险/高危玩家（信誉分 < 500，按分值升序）。 */
export interface V52HighRiskPlayer {
  pteid: string
  reputation_score: number
  reputation_level: string
  false_positives: number
  updated_at: string
}

/** 信誉等级对应的检测策略。 */
export interface V52ReputationPolicy {
  threshold_percent_delta: number
  threshold_multiplier: number
  l0_only: boolean
  force_l2: boolean
  full_feature_report: boolean
}

/** 信誉审计明细（仅 v5.2 口径，scale 固定 0-1000）。 */
export interface V52ReputationLog {
  delta: number
  score_after: number
  reason: string
  source: string
  scale: string
  created_at: string
}

/** 玩家信誉详情（/api/admin/v52/profile/{pteid}/reputation）。 */
export interface V52ReputationDetail {
  pteid: string
  score: number
  max_score: number
  level: string
  legacy_scale_score: number
  policy: V52ReputationPolicy
  logs: V52ReputationLog[]
}

/** 人工调整信誉的返回：applied=false 表示未记账（分值已在上限/下限）。 */
export interface V52ReputationAdjustResult {
  applied: boolean
  reputation: V52ReputationDetail
}

/** 查端回放元数据（不含密钥与内容）。 */
export interface V52ReplayRow {
  id: string
  alert_id: string
  pteid: string
  frames: number
  width: number
  height: number
  fps: number
  duration_ms: number
  size_bytes: number
  sha256: string
  created_at: string
  expires_at: string
}

export interface V52ReplayList {
  items: V52ReplayRow[]
  retention_days: number
}

// ===================== v5.3 管理端只读聚合（/api/admin/v53/**） =====================

/** 硬件指纹明细（§2.5）。 */
export interface V53DeviceFingerprintRow {
  fingerprint_hash: string
  pteid: string
  first_seen_at: string
  last_seen_at: string
  seen_count: number
  /** 该玩家最近一次登录平台（取自设备表，作为环境参考） */
  platform: string
  /** 持有同一指纹的账号数 */
  shared_count: number
  shared_pteids: string[]
  shared_account: boolean
  /** 该玩家过手多枚指纹（设备突变） */
  mutation: boolean
}

export interface V53DeviceList {
  items: V53DeviceFingerprintRow[]
  listed: number
  shared_total: number
  mutation_total: number
  limit: number
  truncated: boolean
}

export interface V53TrendPoint {
  date: string
  session_seconds: number
  online_hours: number
  event_count: number
}

export interface V53HourPoint {
  hour: number
  session_seconds: number
  online_hours: number
}

/** 画像基线：只有聚合值，后端没有逐日明细，不能当作时间序列。 */
export interface V53TrendBaseline {
  exists: boolean
  mean_cps?: number
  cps_std?: number
  mean_aim_smoothness?: number
  aim_smoothness_std?: number
  mean_speed?: number
  speed_std?: number
  sample_count?: number
  total_sessions?: number
  total_detections?: number
  false_positives?: number
  reputation_score?: number
  reputation_level?: string
  profile_version?: number
  first_seen_at?: string
  updated_at?: string
}

export interface V53Trend {
  pteid: string
  days: number
  daily: V53TrendPoint[]
  hourly: V53HourPoint[]
  baseline: V53TrendBaseline
}

export interface V53Policy {
  threshold_percent_delta: number
  threshold_multiplier: number
  l0_only: boolean
  force_l2: boolean
  full_feature_report: boolean
}

export interface V53ReputationBucket {
  level: string
  min_score: number
  max_score: number
  count: number
  average_score: number
  share: number
  sample_policy: V53Policy
}

export interface V53Rule {
  event: string
  label: string
  delta: number
}

export interface V53ReputationOverview {
  total: number
  buckets: V53ReputationBucket[]
  rules: V53Rule[]
  initial_score: number
  min_score: number
  max_score: number
  clean_hour_bonus_cap: number
  average_score: number
  lowest_score: number
  highest_score: number
}

// ===================== v5.4 性能与安全加固（APM / 安全审计 / 密钥管理） =====================
// 与后端冻结契约严格对齐；时间戳一律用 ISO 字符串，不做 Date 化处理。

/** APM 指标目录项：指标名、展示名、单位与所属分组（system / game / detect）。 */
export interface V54ApmCatalogEntry {
  name: string
  label: string
  unit: string
  group: string
}

/** APM 概览 KPI：窗口内聚合值，非时序。 */
export interface V54ApmCards {
  online_clients: number
  avg_cpu_percent: number
  avg_mem_mb: number
  avg_fps_impact_percent: number
  false_positive_rate: number
  sample_count: number
  client_versions: number
}

export interface V54ApmOverview {
  hours: number
  generated_at: string
  cards: V54ApmCards
  catalog: V54ApmCatalogEntry[]
  available_platforms: string[]
  available_versions: string[]
}

/** 单个指标在某一时刻的分位聚合点。 */
export interface V54ApmTrendPoint {
  t: string
  avg: number
  p50: number
  p95: number
  p99: number
  max: number
  count: number
}

export interface V54ApmTrend {
  metric: string
  hours: number
  unit: string
  label: string
  points: V54ApmTrendPoint[]
}

export interface V54ApmMatrixCell {
  platform: string
  metric: string
  p95: number
}

export interface V54ApmMatrix {
  metrics: { name: string; label: string; unit: string }[]
  platforms: string[]
  cells: V54ApmMatrixCell[]
}

/** 某客户端版本相对基线的 P95 与回归判定。 */
export interface V54ApmVersionRow {
  client_ver: string
  p95: number
  sample_count: number
  baseline: boolean
  delta_percent: number
  regressed: boolean
}

export interface V54ApmVersionRegression {
  metric: string
  unit: string
  label: string
  regression_threshold_percent: number
  versions: V54ApmVersionRow[]
}

/** 指标在某一小时的异常点（相对均值/标准差的 sigma 偏离）。 */
export interface V54ApmAnomaly {
  hour: string
  value: number
  threshold: number
  mean: number
  stddev: number
  sigma: number
}

export interface V54ApmAnomalyList {
  metric: string
  platform: string
  items: V54ApmAnomaly[]
}

export interface V54ApmAlert {
  id: string
  alert_name: string
  metric_name: string
  platform: string
  client_ver: string
  level: string
  threshold: number
  observed: number
  message: string
  status: string
  occurred_at: string
  acked_by: string
  acked_at: string
}

export interface V54ApmAlertList {
  items: V54ApmAlert[]
  open_total: number
}

/** 客户端安全事件；evidence 为后端存储的原始证据串（多为 JSON 文本）。 */
export interface V54SecurityEvent {
  id: string
  pteid: string
  event_type: string
  level: string
  client_version: string
  platform: string
  detail: string
  evidence: string
  occurred_at: string
  received_at: string
  seq: number
  hash: string
  prev_hash: string
}

export interface V54SecurityOverview {
  cards: {
    total: number
    critical: number
    high: number
    by_type: { type: string; count: number }[]
    by_level: { level: string; count: number }[]
    chain_ok: boolean
    chain_checked: number
    chain_broken_at: number
  }
  recent: V54SecurityEvent[]
}

export interface V54SecurityEventList {
  items: V54SecurityEvent[]
  total: number
}

/**
 * 审计哈希链校验结果。
 * 后端字段名为 brokenAtSeq；为兼容后续可能的下划线命名，额外保留可选 broken_at。
 */
export interface V54SecurityChain {
  ok: boolean
  checked: number
  brokenAtSeq?: number
  broken_at?: number
  detail: string
}

export interface V54AttestationRow {
  id: string
  pteid: string
  platform: string
  client_version: string
  status: string
  reason: string
  elapsed_ms: number
  code_hash: string
  config_hash: string
  issued_at: string
  created_at: string
}

export interface V54AttestationList {
  items: V54AttestationRow[]
  pass_rate: number
  total: number
}

export interface V54KnownHash {
  id: string
  label: string
  kind: string
  hash: string
  active: boolean
  created_by: string
  created_at: string
}

export interface V54KnownHashList {
  items: V54KnownHash[]
}

/** 托管密钥（层级派生 + 轮换生命周期）。 */
export interface V54ManagedKey {
  id: string
  key_id: string
  purpose: string
  algorithm: string
  state: string
  version: number
  derived_from: string
  fingerprint: string
  created_by: string
  note: string
  created_at: string
  activated_at: string
  rotated_at: string
  expires_at: string
  revoked_at: string
  revoke_reason: string
}

export interface V54ManagedKeyList {
  items: V54ManagedKey[]
  root_configured: boolean
  rotation_days: number
  by_state: Record<string, number>
  purposes: string[]
}

export interface V54KeyAuditRow {
  id: string
  key_id: string
  action: string
  from_state: string
  to_state: string
  operator: string
  note: string
  created_at: string
  prev_hash: string
  hash: string
}

export interface V54KeyAudit {
  chain: V54SecurityChain
  items: V54KeyAuditRow[]
}

// ===================== DF（Deep Fortress）Alpha 1.0.0 · 新增端点契约 =====================
// 与后端并行新增的端点对齐。字段一律可选：后端仍在演进，缺字段按「无数据」渲染，
// 前端不假设固定形状，也不使用 any。

/** BI 实时大屏快照（/bi/realtime）内联的最新红屏事件行。 */
export interface BiRealtimeRedscreen {
  alertId?: string
  cheatType?: string
  level?: string
  state?: string
  occurredAt?: string
}

/** BI 实时大屏快照（/bi/realtime）：在线数、近期检测/红屏量、待查验与最新红屏事件流。 */
export interface BiRealtime {
  online?: number
  detection_last_hour?: number
  detection_last_24h?: number
  redscreen_last_hour?: number
  redscreen_last_24h?: number
  pending_inspect?: number
  recent_redscreens?: BiRealtimeRedscreen[]
}

/** DF §4.1.1 流式检测单阶段耗时（/df/stream/metrics.stageTimings 的值）。 */
export interface DfStageTiming {
  p50Ms?: number
  p95Ms?: number
  count?: number
}

/** DF §4.1.1 流式检测运行指标（/df/stream/metrics），A18 延迟观测面。 */
export interface DfStreamMetrics {
  p50Ms?: number
  p95Ms?: number
  p99Ms?: number
  meanMs?: number
  maxMs?: number
  eventsProcessed?: number
  detections?: number
  ringBufferDrops?: number
  ringBufferSize?: number
  ringBufferCapacity?: number
  aiRefinements?: number
  stageTimings?: Record<string, DfStageTiming>
  idleCpuPercentEstimate?: number
}

/** §4.3.2 告警降噪统计（/alerts/noise/stats，验收 A23 降噪率的量化口径）。 */
export interface AlertNoiseStats {
  windowHours?: number
  rawCount?: number
  aggregatedCount?: number
  suppressedCount?: number
  /** (raw - aggregated) / raw，0~1。 */
  reductionRate?: number
}

/** §4.3.2 聚合告警组（/alerts/groups → rows，实体直出，字段为驼峰）。 */
export interface AlertNoiseGroup {
  id?: string
  tenantId?: string
  playerId?: string
  familyCode?: string
  ruleId?: string
  severity?: number
  priority?: string
  signalCount?: number
  rawAlertIds?: string
  status?: string
  firstSeenAt?: string
  lastSeenAt?: string
  createdAt?: string
}

/** §4.3.2 跨规则家族相关性（/alerts/groups → correlated）。 */
export interface AlertCorrelation {
  familyCode?: string
  groupCount?: number
  signalCount?: number
  maxSeverity?: number
  groupIds?: string[]
}

/** §4.3.2 聚合组列表响应。 */
export interface AlertNoiseGroupPage {
  rows?: AlertNoiseGroup[]
  total?: number
  page?: number
  totalPages?: number
  correlated?: AlertCorrelation[]
}

/** §4.3.2 误报抑制规则。 */
export interface AlertSuppressionRule {
  id?: string
  name?: string
  pattern?: string
  familyCode?: string
  reason?: string
  enabled?: boolean
  hitCount?: number
  lastHitAt?: string
  createdBy?: string
  createdAt?: string
}

/** §4.3.3 自动化规则（/automation/rules）。 */
export interface AutomationRuleRow {
  code?: string
  name?: string
  trigger?: string
  action?: string
  enabled?: boolean
  threshold?: number
  windowMin?: number
  cooldownMin?: number
  builtin?: boolean
  lastFiredAt?: string
  updatedAt?: string
}

/** §4.3.3 自动化执行审计行（/automation/executions → rows）。 */
export interface AutomationExecutionRow {
  id?: number
  ruleCode?: string
  actionCode?: string
  status?: string
  detail?: string
  reversible?: boolean
  reverted?: boolean
  revertDetail?: string
  executedAt?: string
  executedBy?: string
}

export interface AutomationExecutionPage {
  rows?: AutomationExecutionRow[]
  total?: number
  page?: number
  totalPages?: number
}

/** §4.2.2 插件市场条目（/plugins，下划线字段）。 */
export interface PluginMarketRow {
  plugin_id?: string
  name?: string
  description?: string
  type?: string
  author?: string
  plugin_version?: string
  status?: string
  downloads?: number
  avg_rating?: number
  rating_count?: number
  created_at?: string
  published_at?: string
}

export interface PluginMarketList {
  rows?: PluginMarketRow[]
  total?: number
  page?: number
  total_pages?: number
}

/** §4.2.2 插件运行时条目（/plugins/runtime，驼峰字段）。declaredApis 为逗号分隔串。 */
export interface PluginRuntimeRow {
  id?: string
  name?: string
  version?: string
  state?: string
  cpuMs?: number
  errorCount?: number
  declaredApis?: string
  classPath?: string
  loadedAt?: string
  updatedAt?: string
}

/** §4.2.3 单项配额用量。 */
export interface TenantQuotaMetric {
  used?: number
  max?: number
}

/** §4.2.3 单租户配额视图（/tenant/quota → rows）。 */
export interface TenantQuotaRow {
  tenantId?: string
  players?: TenantQuotaMetric
  detectionVolume?: TenantQuotaMetric
  storageMb?: TenantQuotaMetric
  updatedAt?: string
}

export interface TenantQuotaView {
  rows?: TenantQuotaRow[]
  total?: number
}

/** §4.2.3 计费计量流水行（/tenant/usage → rows）。 */
export interface TenantUsageRow {
  id?: number
  tenantId?: string
  metric?: string
  quantity?: number
  unitPrice?: number
  amount?: number
  occurredAt?: string
}

export interface TenantUsageView {
  tenantId?: string
  rows?: TenantUsageRow[]
  rowCount?: number
  totalAmount?: number
}