/**
 * 与 PACC 管控后端交互的接口定义与薄封装。
 *
 * 这个包只定义接口，不实现后端：所有路径都挂在 `/api/prl/...` 下，由管控后端提供
 * （规则版本库见设计文档 §2.13.1，静态分析见 §2.14，调试见 §2.15）。
 * 调用方可以通过 props 注入 `baseUrl`（换网关前缀）和 `fetcher`（带上鉴权头、重试等）。
 */
import type {
  AnalysisComplexity,
  Breakpoint,
  DebugState,
  Diagnostic,
  DiagnosticSeverity,
  PrlFetcher,
  RuleProfile,
  RuleVersion,
} from './types'

/** 缺省基址。 */
export const PRL_API_BASE = '/api/prl'

export interface PrlClientOptions {
  baseUrl?: string
  fetcher?: PrlFetcher
  requestTimeoutMs?: number
}

/** 接口调用失败。组件拿到它就把错误如实显示出来，不要伪造成功。 */
export class PrlApiError extends Error {
  readonly status: number
  readonly path: string

  constructor(path: string, status: number, message: string) {
    super(message)
    this.name = 'PrlApiError'
    this.status = status
    this.path = path
  }
}

/** 是否配置了后端接入点；没配置的组件走空态 + 演示数据。 */
export function isPrlEndpointConfigured(options: PrlClientOptions): boolean {
  return Boolean(options.baseUrl || options.fetcher)
}

/** 把任意异常整理成能直接显示的一句话。 */
export function describePrlError(error: unknown): string {
  if (error instanceof PrlApiError) {
    return error.message
  }
  if (error instanceof Error) {
    if (error.name === 'AbortError') {
      return '请求已取消'
    }
    return error.message
  }
  return '未知错误'
}

interface AbortHandle {
  signal: AbortSignal | undefined
  dispose: () => void
}

function createAbort(timeoutMs?: number, external?: AbortSignal): AbortHandle {
  if (!timeoutMs && !external) {
    return { signal: undefined, dispose: () => undefined }
  }
  const controller = new AbortController()
  const timer = timeoutMs ? setTimeout(() => controller.abort(), timeoutMs) : undefined
  const relay = () => controller.abort()
  if (external) {
    if (external.aborted) {
      controller.abort()
    } else {
      external.addEventListener('abort', relay)
    }
  }
  return {
    signal: controller.signal,
    dispose: () => {
      if (timer !== undefined) {
        clearTimeout(timer)
      }
      external?.removeEventListener('abort', relay)
    },
  }
}

async function readErrorBody(response: Response): Promise<string> {
  try {
    const text = await response.text()
    return text.length > 300 ? `${text.slice(0, 300)}…` : text
  } catch {
    return ''
  }
}

async function request<T>(
  options: PrlClientOptions,
  path: string,
  init: RequestInit,
  signal?: AbortSignal,
): Promise<T> {
  const base = (options.baseUrl ?? PRL_API_BASE).replace(/\/+$/, '')
  const fetcher: PrlFetcher = options.fetcher ?? ((url, requestInit) => fetch(url, requestInit))
  const abort = createAbort(options.requestTimeoutMs, signal)
  try {
    const response = await fetcher(`${base}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...(init.headers ?? {}) },
      signal: abort.signal,
    })
    if (!response.ok) {
      const body = await readErrorBody(response)
      throw new PrlApiError(
        path,
        response.status,
        `接口 ${path} 返回 ${response.status}${body ? `：${body}` : ''}`,
      )
    }
    if (response.status === 204) {
      return undefined as unknown as T
    }
    return (await response.json()) as T
  } finally {
    abort.dispose()
  }
}

// ---------------------------------------------------------------------------
// 静态分析（§2.14）：本地检查的快 vs 服务端的准
// ---------------------------------------------------------------------------

export interface AnalyzeRequest {
  source: string
  /** 已有规则名时带上，服务端才能做规则冲突检测（§2.14.2）。 */
  ruleName?: string
}

/**
 * 服务端诊断项。`code` 的前缀就是引擎的诊断分类：
 * `PRL-P` 语法、`PRL-T` 类型、`PRL-N` 空指针、`PRL-L` 无限循环、`PRL-S` 安全、`PRL-C` 冲突。
 */
export interface ServerDiagnostic {
  line: number
  column?: number
  severity: DiagnosticSeverity
  code: string
  message: string
  hint?: string
}

export interface AnalyzeResponse {
  diagnostics: ServerDiagnostic[]
  complexity?: AnalysisComplexity
  /** 引擎版本，排查「本地过、服务端不过」时有用。 */
  engineVersion?: string
}

const PRL_CODE_LABELS: Readonly<Record<string, string>> = {
  'PRL-P': '语法',
  'PRL-T': '类型',
  'PRL-N': '空指针',
  'PRL-L': '无限循环',
  'PRL-S': '安全',
  'PRL-C': '规则冲突',
}

/** 诊断码前缀的中文含义；未知码原样返回。 */
export function describeDiagnosticCode(code: string | undefined): string {
  if (!code) {
    return '未分类'
  }
  const prefix = code.slice(0, 5).toUpperCase()
  return PRL_CODE_LABELS[prefix] ?? code
}

export function diagnosticsFromServer(response: AnalyzeResponse): Diagnostic[] {
  return response.diagnostics.map((item) => ({
    line: item.line,
    column: item.column,
    severity: item.severity,
    source: 'server' as const,
    code: item.code,
    message: item.message,
    hint: item.hint,
  }))
}

/** `POST /api/prl/analyze` */
export function analyzeSource(
  options: PrlClientOptions,
  body: AnalyzeRequest,
  signal?: AbortSignal,
): Promise<AnalyzeResponse> {
  return request<AnalyzeResponse>(options, '/analyze', { method: 'POST', body: JSON.stringify(body) }, signal)
}

// ---------------------------------------------------------------------------
// 调试（§2.15.1）
// ---------------------------------------------------------------------------

export interface DebugSessionRequest {
  ruleName: string
  source: string
  ruleVersion?: string
  breakpoints?: Breakpoint[]
  /** 条件断点/日志点由服务端按断点对象处理，这里不实现调试协议。 */
}

export interface DebugStepRequest {
  sessionId: string
  /** `into` 进入 lambda 调用栈，`over` 跳过。 */
  mode?: 'into' | 'over'
}

export interface DebugSessionRef {
  sessionId: string
}

export interface DebugStateResponse {
  state: DebugState
}

export interface BreakpointsRequest {
  sessionId: string
  breakpoints: Breakpoint[]
}

export interface BreakpointsResponse {
  breakpoints: Breakpoint[]
}

/** `POST /api/prl/debug/session` */
export function createDebugSession(
  options: PrlClientOptions,
  body: DebugSessionRequest,
  signal?: AbortSignal,
): Promise<DebugStateResponse> {
  return request<DebugStateResponse>(
    options,
    '/debug/session',
    { method: 'POST', body: JSON.stringify(body) },
    signal,
  )
}

/** `POST /api/prl/debug/step` —— 单步（进入/跳过）。 */
export function stepDebug(
  options: PrlClientOptions,
  body: DebugStepRequest,
  signal?: AbortSignal,
): Promise<DebugStateResponse> {
  return request<DebugStateResponse>(options, '/debug/step', { method: 'POST', body: JSON.stringify(body) }, signal)
}

/** `POST /api/prl/debug/resume` —— 继续执行到下一个断点。 */
export function resumeDebug(
  options: PrlClientOptions,
  body: DebugSessionRef,
  signal?: AbortSignal,
): Promise<DebugStateResponse> {
  return request<DebugStateResponse>(
    options,
    '/debug/resume',
    { method: 'POST', body: JSON.stringify(body) },
    signal,
  )
}

/** `POST /api/prl/debug/stop` —— 结束会话。 */
export function stopDebug(
  options: PrlClientOptions,
  body: DebugSessionRef,
  signal?: AbortSignal,
): Promise<DebugStateResponse> {
  return request<DebugStateResponse>(options, '/debug/stop', { method: 'POST', body: JSON.stringify(body) }, signal)
}

/** `PUT /api/prl/debug/breakpoints` —— 覆盖式更新断点表。 */
export function setBreakpoints(
  options: PrlClientOptions,
  body: BreakpointsRequest,
  signal?: AbortSignal,
): Promise<BreakpointsResponse> {
  return request<BreakpointsResponse>(
    options,
    '/debug/breakpoints',
    { method: 'PUT', body: JSON.stringify(body) },
    signal,
  )
}

// ---------------------------------------------------------------------------
// 性能分析（§2.15.2）
// ---------------------------------------------------------------------------

export interface ProfileQuery {
  ruleName: string
  version?: string
  /** 统计窗口，如 `1h` / `24h`。 */
  window?: string
}

/** `GET /api/prl/profiles/{ruleName}` */
export function fetchRuleProfile(
  options: PrlClientOptions,
  query: ProfileQuery,
  signal?: AbortSignal,
): Promise<RuleProfile> {
  const params = new URLSearchParams()
  if (query.version) {
    params.set('version', query.version)
  }
  if (query.window) {
    params.set('window', query.window)
  }
  const search = params.toString()
  const suffix = search.length > 0 ? `?${search}` : ''
  return request<RuleProfile>(
    options,
    `/profiles/${encodeURIComponent(query.ruleName)}${suffix}`,
    { method: 'GET' },
    signal,
  )
}

// ---------------------------------------------------------------------------
// 版本管理（§2.13）
// ---------------------------------------------------------------------------

export interface VersionListResponse {
  versions: RuleVersion[]
}

export interface CreateDraftRequest {
  ruleName: string
  version: string
  source: string
  author: string
  note?: string
}

export interface CanaryRequest {
  ruleName: string
  version: string
  /** 灰度比例，设计文档默认 1%。 */
  playerPercent: number
}

export interface ApproveRequest {
  ruleName: string
  version: string
  approvedBy: string
}

export interface RollbackRequest {
  ruleName: string
  targetVersion: string
}

/** `GET /api/prl/versions?ruleName=` */
export function listRuleVersions(
  options: PrlClientOptions,
  query: { ruleName?: string },
  signal?: AbortSignal,
): Promise<VersionListResponse> {
  const suffix = query.ruleName ? `?ruleName=${encodeURIComponent(query.ruleName)}` : ''
  return request<VersionListResponse>(options, `/versions${suffix}`, { method: 'GET' }, signal)
}

/** `POST /api/prl/versions` —— 提交 draft 版本。 */
export function createDraftVersion(
  options: PrlClientOptions,
  body: CreateDraftRequest,
  signal?: AbortSignal,
): Promise<RuleVersion> {
  return request<RuleVersion>(options, '/versions', { method: 'POST', body: JSON.stringify(body) }, signal)
}

/** `POST /api/prl/versions/{ruleName}/{version}/canary` —— 进入灰度测试。 */
export function startCanary(
  options: PrlClientOptions,
  body: CanaryRequest,
  signal?: AbortSignal,
): Promise<RuleVersion> {
  const path = `/versions/${encodeURIComponent(body.ruleName)}/${encodeURIComponent(body.version)}/canary`
  return request<RuleVersion>(
    options,
    path,
    { method: 'POST', body: JSON.stringify({ playerPercent: body.playerPercent }) },
    signal,
  )
}

/** `POST /api/prl/versions/{ruleName}/{version}/approve` —— 审批发布为 active。 */
export function approveVersion(
  options: PrlClientOptions,
  body: ApproveRequest,
  signal?: AbortSignal,
): Promise<RuleVersion> {
  const path = `/versions/${encodeURIComponent(body.ruleName)}/${encodeURIComponent(body.version)}/approve`
  return request<RuleVersion>(
    options,
    path,
    { method: 'POST', body: JSON.stringify({ approvedBy: body.approvedBy }) },
    signal,
  )
}

/** `POST /api/prl/versions/{ruleName}/rollback` —— 一键回滚到指定版本。 */
export function rollbackVersion(
  options: PrlClientOptions,
  body: RollbackRequest,
  signal?: AbortSignal,
): Promise<RuleVersion> {
  const path = `/versions/${encodeURIComponent(body.ruleName)}/rollback`
  return request<RuleVersion>(
    options,
    path,
    { method: 'POST', body: JSON.stringify({ targetVersion: body.targetVersion }) },
    signal,
  )
}