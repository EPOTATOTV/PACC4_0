/**
 * 版本管理界面（§2.13）。
 *
 * 发布流程：提交草稿 → 灰度测试（1% 玩家，误报率 > 2% 拒绝）→ 管理员审批 → active；异常时一键回滚。
 * 四个操作按钮的可用性完全由当前版本状态决定，禁用时给出原因，不做「可点但无效果」的假按钮。
 * 操作走 api.ts；没接后端时如实报错，不假装成功。列表数据可以先用前端演示数据看界面。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  approveVersion,
  createDraftVersion,
  describePrlError,
  isPrlEndpointConfigured,
  listRuleVersions,
  rollbackVersion,
  startCanary,
  type PrlClientOptions,
} from './api'
import type { PrlEndpointProps, RuleVersion, RuleVersionStatus, VersionAction } from './types'
import { Badge, DemoMark, EmptyState, InlineError, Panel } from './ui'
import type { BadgeTone } from './ui'

export interface VersionActionPayload {
  note?: string
  author?: string
  approver?: string
  playerPercent?: number
  targetVersion?: string
  source?: string
}

export interface RuleVersionManagerProps extends PrlEndpointProps {
  ruleName?: string
  /** 外部注入的版本列表；给了就不请求后端。 */
  versions?: RuleVersion[]
  loading?: boolean
  error?: string
  /** 提交草稿时用的源码。 */
  source?: string
  author?: string
  approver?: string
  /** 灰度比例，缺省 1%（§2.13.2）。 */
  canaryPercent?: number
  /** 接管四个操作；给了就不再调用 api。 */
  onAction?: (action: VersionAction, target: RuleVersion, payload: VersionActionPayload) => void | Promise<void>
  onSelect?: (version: RuleVersion) => void
}

const STATUS_META: Readonly<Record<RuleVersionStatus, { label: string; tone: BadgeTone }>> = {
  draft: { label: '草稿', tone: 'neutral' },
  testing: { label: '灰度测试', tone: 'gold' },
  active: { label: '生效中', tone: 'ok' },
  deprecated: { label: '已弃用', tone: 'muted' },
  disabled: { label: '已禁用', tone: 'error' },
}

const ACTION_ORDER: readonly VersionAction[] = ['draft', 'canary', 'approve', 'rollback']

const ACTION_LABEL: Readonly<Record<VersionAction, string>> = {
  draft: '提交草稿',
  canary: '进入灰度',
  approve: '审批发布',
  rollback: '一键回滚',
}

/** 空列表用同一个常量引用，避免每次渲染都新建数组把下游 useMemo 的依赖打穿。 */
const NO_VERSIONS: RuleVersion[] = []

/** 可用性由状态决定；禁用时给出原因，直接显示在 title 与提示行里。 */
function availability(action: VersionAction, version: RuleVersion): { enabled: boolean; reason: string } {
  switch (action) {
    case 'draft':
      return version.status === 'draft'
        ? { enabled: true, reason: '' }
        : { enabled: false, reason: '只有草稿版本可以提交/更新，其它状态要改就新建草稿' }
    case 'canary':
      return version.status === 'draft'
        ? { enabled: true, reason: '' }
        : { enabled: false, reason: '先提交草稿，再进入灰度测试' }
    case 'approve':
      return version.status === 'testing'
        ? { enabled: true, reason: '' }
        : { enabled: false, reason: '只有灰度测试中的版本可以审批发布' }
    case 'rollback':
      return (version.status === 'active' || version.status === 'deprecated') && version.rollbackTo
        ? { enabled: true, reason: '' }
        : { enabled: false, reason: '当前版本没有可回滚的目标版本（rollback_to 为空）' }
  }
}

function formatTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** 前端内置演示数据。 */
function createDemoVersions(ruleName: string): RuleVersion[] {
  const name = ruleName || 'kill_aura_detection'
  return [
    {
      id: 'demo-1',
      ruleName: name,
      version: '1.3.0',
      status: 'draft',
      author: 'pacc-dev',
      approvedBy: null,
      createdAt: '2026-09-24T09:12:00Z',
      checksum: '9f2c41a75be0d38c1a44f0b7c9e2d6105ab3f8c74d1e2690bb5037ce81df4a29',
      rollbackTo: null,
      note: '把 click_pattern 提成变量，避免 when 段重复求值',
    },
    {
      id: 'demo-2',
      ruleName: name,
      version: '1.2.0',
      status: 'testing',
      author: 'pacc-dev',
      approvedBy: null,
      createdAt: '2026-09-20T02:40:00Z',
      checksum: '3ba7e0c9d1f24b58c07a6e3d91b4f8026cc5ae17d9382470fdb16a04e75c9b83',
      rollbackTo: '1.1.0',
      canary: { playerPercent: 1, falsePositiveRate: 0.42, sampleSize: 12480 },
    },
    {
      id: 'demo-3',
      ruleName: name,
      version: '1.1.0',
      status: 'active',
      author: 'pacc-dev',
      approvedBy: 'sec-lead',
      createdAt: '2026-09-02T11:05:00Z',
      checksum: 'c14d7a2069be53f8d20e6b41ac9807ff3251ba46d8c0937ef2a6d4b51803c7ea',
      rollbackTo: '1.0.0',
    },
    {
      id: 'demo-4',
      ruleName: name,
      version: '1.0.0',
      status: 'deprecated',
      author: 'pacc-dev',
      approvedBy: 'sec-lead',
      createdAt: '2026-08-11T06:20:00Z',
      checksum: '70e5b19c3fa842d6b0c17e59df2418ab6c30f7e294ba1857d0e6c4f2391ab5d8',
      rollbackTo: null,
    },
  ]
}

export function RuleVersionManager({
  ruleName = '',
  versions,
  loading = false,
  error: externalError,
  source = '',
  author = '',
  approver = '',
  canaryPercent = 1,
  onAction,
  onSelect,
  baseUrl,
  fetcher,
  requestTimeoutMs,
}: RuleVersionManagerProps) {
  const [fetched, setFetched] = useState<RuleVersion[] | null>(null)
  const [demo, setDemo] = useState<RuleVersion[] | null>(null)
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<VersionAction | null>(null)
  const [busy, setBusy] = useState(false)
  const [reloadSeq, setReloadSeq] = useState(0)

  const endpoint = useMemo<PrlClientOptions>(
    () => ({ baseUrl, fetcher, requestTimeoutMs }),
    [baseUrl, fetcher, requestTimeoutMs],
  )
  const endpointReady = isPrlEndpointConfigured(endpoint)
  const canFetch = endpointReady && !versions

  useEffect(() => {
    if (!canFetch) {
      return
    }
    const controller = new AbortController()
    let cancelled = false
    listRuleVersions(endpoint, { ruleName: ruleName || undefined }, controller.signal)
      .then((response) => {
        if (cancelled) {
          return
        }
        setFetched(response.versions)
        setStatus('ready')
        setError(null)
      })
      .catch((caught: unknown) => {
        if (cancelled) {
          return
        }
        setFetched(null)
        setStatus('error')
        setError(describePrlError(caught))
      })
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [canFetch, endpoint, reloadSeq, ruleName])

  const list = versions ?? demo ?? fetched ?? NO_VERSIONS
  // 首次请求期间 status 还是 idle，这里一并算作加载中；effect 里不再同步 setState。
  const busyOrLoading = loading || status === 'loading' || busy || (canFetch && status === 'idle')
  const errorMessage = externalError ?? error

  const selected = useMemo(
    () => list.find((item) => item.id === selectedId) ?? list[0] ?? null,
    [list, selectedId],
  )

  const select = useCallback(
    (version: RuleVersion) => {
      setSelectedId(version.id)
      onSelect?.(version)
    },
    [onSelect],
  )

  const buildPayload = useCallback(
    (action: VersionAction, version: RuleVersion): VersionActionPayload => {
      if (action === 'draft') {
        return { source, author, note: version.note }
      }
      if (action === 'canary') {
        return { playerPercent: canaryPercent }
      }
      if (action === 'approve') {
        return { approver }
      }
      return { targetVersion: version.rollbackTo ?? '' }
    },
    [approver, author, canaryPercent, source],
  )

  const runAction = useCallback(
    async (action: VersionAction, version: RuleVersion) => {
      const payload = buildPayload(action, version)
      setConfirming(null)
      setBusy(true)
      setError(null)
      try {
        if (onAction) {
          await onAction(action, version, payload)
          return
        }
        if (!endpointReady) {
          setError('未接入后端（未提供 baseUrl / fetcher，也没有 onAction）：这次操作没有提交，本地状态也没有变动。')
          return
        }
        if (action === 'draft') {
          await createDraftVersion(endpoint, {
            ruleName: version.ruleName,
            version: version.version,
            source: payload.source ?? '',
            author: payload.author ?? '',
            note: payload.note,
          })
        } else if (action === 'canary') {
          await startCanary(endpoint, {
            ruleName: version.ruleName,
            version: version.version,
            playerPercent: payload.playerPercent ?? 1,
          })
        } else if (action === 'approve') {
          await approveVersion(endpoint, {
            ruleName: version.ruleName,
            version: version.version,
            approvedBy: payload.approver ?? '',
          })
        } else {
          await rollbackVersion(endpoint, {
            ruleName: version.ruleName,
            targetVersion: payload.targetVersion ?? '',
          })
        }
        setReloadSeq((seq) => seq + 1)
      } catch (caught) {
        setError(`${ACTION_LABEL[action]}失败：${describePrlError(caught)}`)
      } finally {
        setBusy(false)
      }
    },
    [buildPayload, endpoint, endpointReady, onAction],
  )

  const confirmText = useCallback(
    (action: VersionAction, version: RuleVersion): string => {
      if (action === 'draft') {
        return `把当前源码提交为 ${version.ruleName} v${version.version} 的草稿${author ? `（作者 ${author}）` : ''}。`
      }
      if (action === 'canary') {
        return `让 ${version.ruleName} v${version.version} 进入灰度，先覆盖 ${canaryPercent}% 玩家；误报率超过 2% 会被拒绝。`
      }
      if (action === 'approve') {
        return `审批通过 ${version.ruleName} v${version.version} 并发布为生效版本；当前 active 版本会保留为 deprecated。`
      }
      return `把 ${version.ruleName} 回滚到 v${version.rollbackTo ?? '—'}；当前版本会转为 deprecated。`
    },
    [author, canaryPercent],
  )

  return (
    <Panel
      title="版本管理"
      subtitle={ruleName ? `规则 ${ruleName}` : '未指定规则名'}
      index={0}
      actions={
        <div className="prl-versions__actions">
          <Badge tone={demo ? 'gold' : versions ? 'neutral' : 'muted'}>
            {versions ? 'props 注入' : demo ? '前端演示' : fetched ? '后端接口' : '无数据'}
          </Badge>
          <button
            type="button"
            className="prl-btn prl-btn--ghost prl-btn--sm"
            disabled={busyOrLoading || !endpointReady}
            onClick={() => setReloadSeq((seq) => seq + 1)}
            title={endpointReady ? '重新拉取版本列表' : '未接入后端，无法拉取'}
          >
            刷新
          </button>
        </div>
      }
    >
      {demo && (
        <p className="prl-muted prl-versions__note">
          <DemoMark /> 版本列表是前端内置的示例数据，四个操作按钮只做可用性演示。
        </p>
      )}
      {busyOrLoading && <p className="prl-muted">正在处理…</p>}
      {errorMessage && <InlineError message={errorMessage} />}

      {list.length === 0 && !busyOrLoading && !errorMessage && (
        <EmptyState
          title="没有版本记录"
          hint={
            endpointReady
              ? '后端没返回这条规则的版本；确认规则名，或先去编辑器提交一份草稿。'
              : '未接入后端（未提供 baseUrl / fetcher），也没有通过 props 注入 versions。'
          }
          action={
            <button
              type="button"
              className="prl-btn prl-btn--ghost prl-btn--sm"
              onClick={() => setDemo(createDemoVersions(ruleName))}
            >
              载入演示数据
            </button>
          }
        />
      )}

      {list.length > 0 && (
        <div className="prl-versions">
          <ul className="prl-version-list">
            {list.map((version) => {
              const meta = STATUS_META[version.status]
              const isSelected = selected?.id === version.id
              return (
                <li key={version.id}>
                  <button
                    type="button"
                    className={`prl-version${isSelected ? ' prl-version--on' : ''}`}
                    onClick={() => select(version)}
                  >
                    <span className="prl-version__top">
                      <span className="prl-mono prl-version__no">v{version.version}</span>
                      <Badge tone={meta.tone}>{meta.label}</Badge>
                    </span>
                    <span className="prl-version__meta">
                      <span>作者 {version.author || '—'}</span>
                      <span>审批 {version.approvedBy ?? '—'}</span>
                    </span>
                    <span className="prl-version__meta">
                      <span>{formatTime(version.createdAt)}</span>
                      <span className="prl-mono" title={version.checksum}>
                        {version.checksum.slice(0, 8)}
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>

          {selected && (
            <div className="prl-version-detail">
              <h3 className="prl-section-title">
                操作 · v{selected.version}
                <Badge tone={STATUS_META[selected.status].tone}>{STATUS_META[selected.status].label}</Badge>
              </h3>
              {selected.note && <p className="prl-muted">变更说明：{selected.note}</p>}
              {selected.canary && (
                <p className="prl-muted">
                  灰度 {selected.canary.playerPercent}% · 样本 {selected.canary.sampleSize} · 误报率{' '}
                  {(selected.canary.falsePositiveRate * 100).toFixed(2)}%
                </p>
              )}

              <div className="prl-version-detail__buttons">
                {ACTION_ORDER.map((action) => {
                  const state = availability(action, selected)
                  return (
                    <button
                      key={action}
                      type="button"
                      className={`prl-btn${action === 'approve' ? '' : ' prl-btn--ghost'}`}
                      disabled={!state.enabled || busy}
                      title={state.enabled ? confirmText(action, selected) : state.reason}
                      onClick={() => setConfirming(action)}
                    >
                      {ACTION_LABEL[action]}
                    </button>
                  )
                })}
              </div>

              <ul className="prl-version-detail__reasons">
                {ACTION_ORDER.filter((action) => !availability(action, selected).enabled).map((action) => (
                  <li key={action} className="prl-muted">
                    {ACTION_LABEL[action]}：{availability(action, selected).reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {confirming && selected && (
        <div className="prl-modal" role="dialog" aria-modal="true" aria-label={`${ACTION_LABEL[confirming]}确认`}>
          <div className="prl-modal__panel">
            <h3 className="prl-modal__title">确认{ACTION_LABEL[confirming]}</h3>
            <p className="prl-modal__text">{confirmText(confirming, selected)}</p>
            <div className="prl-modal__buttons">
              <button type="button" className="prl-btn prl-btn--ghost" onClick={() => setConfirming(null)}>
                取消
              </button>
              <button type="button" className="prl-btn" onClick={() => void runAction(confirming, selected)}>
                确认执行
              </button>
            </div>
          </div>
        </div>
      )}
    </Panel>
  )
}