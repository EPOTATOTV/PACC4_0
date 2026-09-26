/**
 * PRL 管理端规则编辑器组件包的统一出口。
 * 管理端只需要 `import { Editor, Linter } from '@pacc/prl-editor'`。
 */
export { Editor } from './Editor'
export type { EditorProps } from './Editor'

export { Linter, lintSource } from './Linter'
export type { LinterProps, PrlLintOptions } from './Linter'

export { Debugger } from './Debugger'
export type { DebuggerProps } from './Debugger'

export { Profiler } from './Profiler'
export type { ProfilerProps } from './Profiler'

export { RuleVersionManager } from './RuleVersionManager'
export type { RuleVersionManagerProps, VersionActionPayload } from './RuleVersionManager'

export { Badge, DemoMark, EmptyState, InlineError, Panel } from './ui'
export type { BadgeTone, EmptyStateProps, PanelProps } from './ui'

export type {
  AnalysisComplexity,
  Breakpoint,
  ConfirmRequest,
  DebugAction,
  DebugFrame,
  DebugLogEntry,
  DebugState,
  DebugStatus,
  DebugVariable,
  Diagnostic,
  DiagnosticSeverity,
  DiagnosticSource,
  Hotspot,
  PrlEndpointProps,
  PrlFetcher,
  RuleProfile,
  RuleVersion,
  RuleVersionStatus,
  VersionAction,
} from './types'

export {
  PRL_BLOCK_HEADERS,
  PRL_END_BLOCKS,
  PRL_FUNCTIONS,
  PRL_HOST_TYPES,
  PRL_INDENT,
  PRL_KEYWORDS,
  PRL_METADATA_KEYS,
  PRL_SEVERITIES,
  PRL_STDLIB_NAMES,
  PRL_TYPES,
  PRL_UNITS,
  codeOf,
  extractDeclarations,
  findPrlFunction,
  getPrlCompletions,
  identifierPrefixAt,
  stripComment,
  tokenize,
  tokenizeLine,
} from './prlLanguage'
export type {
  PrlCompletionCandidate,
  PrlCompletionKind,
  PrlCompletionOptions,
  PrlDeclaration,
  PrlFunctionCategory,
  PrlFunctionInfo,
  PrlKeywordInfo,
  PrlToken,
  PrlTokenLine,
  PrlTokenType,
} from './prlLanguage'

export {
  PRL_API_BASE,
  PrlApiError,
  analyzeSource,
  approveVersion,
  createDebugSession,
  createDraftVersion,
  describeDiagnosticCode,
  describePrlError,
  diagnosticsFromServer,
  fetchRuleProfile,
  isPrlEndpointConfigured,
  listRuleVersions,
  resumeDebug,
  rollbackVersion,
  setBreakpoints,
  startCanary,
  stepDebug,
  stopDebug,
} from './api'
export type {
  AnalyzeRequest,
  AnalyzeResponse,
  ApproveRequest,
  BreakpointsRequest,
  BreakpointsResponse,
  CanaryRequest,
  CreateDraftRequest,
  DebugSessionRef,
  DebugSessionRequest,
  DebugStateResponse,
  DebugStepRequest,
  PrlClientOptions,
  ProfileQuery,
  RollbackRequest,
  ServerDiagnostic,
  VersionListResponse,
} from './api'