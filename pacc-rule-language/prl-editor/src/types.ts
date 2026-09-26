/**
 * PRL 管理端编辑器组件包的共享类型。
 *
 * 这里只放「组件之间互通」和「后端响应形状」的类型；PRL 语言本身的关键字/函数表在
 * prlLanguage.ts，与后端交互的请求封装在 api.ts。
 */

// ---------------------------------------------------------------------------
// 后端接入方式
// ---------------------------------------------------------------------------

/** 与 fetch 同形状的可替换请求函数，便于管理端注入自己的鉴权/重试封装。 */
export type PrlFetcher = (url: string, init?: RequestInit) => Promise<Response>

/**
 * 所有需要访问 PACC 管控后端的组件都接受这三个属性。
 * `baseUrl` 缺省为 `/api/prl`；`fetcher` 缺省为全局 fetch。
 */
export interface PrlEndpointProps {
  /** 后端基址，例如 `/api/prl` 或 `https://ptv.example.com/api/prl`。 */
  baseUrl?: string
  /** 自定义请求函数（管理端统一注入鉴权头、重试、埋点等）。 */
  fetcher?: PrlFetcher
  /** 额外的查询参数，部分部署把规则接口挂在带前缀的网关上时用得到。 */
  requestTimeoutMs?: number
}

// ---------------------------------------------------------------------------
// 诊断（本地检查 + 服务端分析）
// ---------------------------------------------------------------------------

/**
 * `local` 是本包纯前端的词法/结构检查，快但可能误报；
 * `server` 是 Java 引擎 `PrlCompiler.compileChecked` 的结果，准但慢。
 */
export type DiagnosticSource = 'local' | 'server'

export type DiagnosticSeverity = 'error' | 'warning' | 'info'

/**
 * 诊断项。服务端的 `code` 取 §2.14 的诊断码前缀：
 * `PRL-P` 语法、`PRL-T` 类型、`PRL-N` 空指针、`PRL-L` 无限循环、`PRL-S` 安全、`PRL-C` 冲突。
 */
export interface Diagnostic {
  line: number
  column?: number
  severity: DiagnosticSeverity
  source: DiagnosticSource
  code?: string
  message: string
  /** 服务端给出的修复建议（§2.14.2 的冲突检测会给建议）。 */
  hint?: string
}

/** 服务端静态分析附带的复杂度/性能预估（§2.14.1）。 */
export interface AnalysisComplexity {
  cyclomaticComplexity: number
  maxNestingDepth: number
  estimatedCostUs?: number
  estimatedMemoryKb?: number
  score?: string
}

// ---------------------------------------------------------------------------
// 调试（§2.15.1）
// ---------------------------------------------------------------------------

export interface Breakpoint {
  id: string
  ruleName: string
  line: number
  /** 条件断点：满足条件时才中断（§2.15.1）。 */
  condition?: string
  enabled: boolean
  /** 日志点：不中断，只记录变量值。 */
  logPoint?: boolean
}

export type DebugStatus = 'idle' | 'running' | 'paused' | 'stopped' | 'error'

export interface DebugVariable {
  name: string
  /** 声明类型，如 `list[AttackEvent]`；宿主对象按 `PlayerContext` 之类显示。 */
  type: string
  /** 已求值的展示字符串；未求值时为空字符串。 */
  value: string
  /** 来源：input 声明 / let 局部变量 / 宿主注入。 */
  scope: 'input' | 'local' | 'host'
}

export interface DebugFrame {
  id: string
  /** 栈帧名，PRL 只有规则级与 lambda 两级。 */
  name: string
  line: number
}

export interface DebugLogEntry {
  seq: number
  line: number
  level: 'info' | 'warn' | 'error'
  message: string
}

export interface DebugState {
  sessionId: string
  ruleName: string
  ruleVersion: string
  status: DebugStatus
  currentLine: number
  variables: DebugVariable[]
  callStack: DebugFrame[]
  output: DebugLogEntry[]
  /** 是否为前端内置演示数据（无后端时的展示用）。 */
  isDemo: boolean
}

/** 调试面板对外抛出的动作；父组件可据此自己接后端。 */
export type DebugAction =
  | { type: 'start' }
  | { type: 'stepInto' }
  | { type: 'stepOver' }
  | { type: 'continue' }
  | { type: 'stop' }

// ---------------------------------------------------------------------------
// 性能分析（§2.15.2）
// ---------------------------------------------------------------------------

export interface Hotspot {
  name: string
  /** 耗时占比，0-100。 */
  percent: number
  /** 原始耗时（毫秒），服务端没给就不显示绝对值。 */
  totalMs?: number
  /** 优化建议，服务端针对热点函数给时优先用它。 */
  suggestion?: string
}

export interface RuleProfile {
  ruleName: string
  ruleVersion: string
  /** 统计窗口内的执行次数。 */
  executions: number
  avgMs: number
  p95Ms: number
  p99Ms: number
  maxMs: number
  memoryPeakKb: number
  hotspots: Hotspot[]
  suggestions: string[]
  /** 数据采样窗口描述，例如「近 1 小时」。 */
  window?: string
}

// ---------------------------------------------------------------------------
// 版本管理（§2.13）
// ---------------------------------------------------------------------------

export type RuleVersionStatus = 'draft' | 'testing' | 'active' | 'deprecated' | 'disabled'

export interface RuleVersion {
  id: string
  ruleName: string
  version: string
  status: RuleVersionStatus
  author: string
  /** 生产规则必须有审批人，draft/testing 阶段为 null。 */
  approvedBy: string | null
  createdAt: string
  /** SHA-256 校验和，列表里只展示前 8 位。 */
  checksum: string
  /** 可回滚到的版本（§2.13.1 的 rollback_to）。 */
  rollbackTo: string | null
  /** 提交说明/变更摘要。 */
  note?: string
  /** 服务端返回的误报率等灰度指标，仅在 testing 阶段有意义。 */
  canary?: {
    playerPercent: number
    falsePositiveRate: number
    sampleSize: number
  }
}

/** 版本管理面板的四个操作。 */
export type VersionAction = 'draft' | 'canary' | 'approve' | 'rollback'

export interface ConfirmRequest {
  action: VersionAction
  version: RuleVersion
}