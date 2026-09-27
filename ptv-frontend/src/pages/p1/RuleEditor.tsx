/**
 * PRL 规则编辑器（设计文档 §3.3 管理端规则管理）。
 *
 * 组件本身来自 `@potatotv/prl-editor`，本页只做接线：
 * 把源码、诊断、端点在三块面板之间传，并补一个「提交草稿」入口。
 *
 * 为什么还要自己补一个提交草稿的入口：版本管理面板的四个按钮都作用在「列表里已有的版本」上，
 * 一条规则第一次入库时列表是空的，面板里点不出任何东西。首版必须由页面来建。
 */
import { useCallback, useMemo, useState } from 'react'
import {
  Editor,
  InlineError,
  Linter,
  Panel,
  RuleVersionManager,
  createDraftVersion,
  describePrlError,
  type Diagnostic,
  type PrlClientOptions,
  type PrlFetcher,
} from '@potatotv/prl-editor'
import '@potatotv/prl-editor/style.css'

/**
 * 编辑器内部的请求封装不认识管理端的挂载路径，baseUrl 换成 /api/admin 这一段即可。
 * 鉴权走同源 HttpOnly cookie（AdminKeyFilter），fetch 默认就是 same-origin，这里写出来是为了显式。
 */
const PRL_BASE = '/api/admin/prl'

const prlFetcher: PrlFetcher = (url, init) => fetch(url, { ...init, credentials: 'same-origin' })

/**
 * 后端宿主在 PRL 标准库之外自接管了这三个转换函数（ptv-backend 的 RuleHostContext）。
 * 不列给本地检查，「未知函数」会在这三个上误报。
 */
const HOST_FUNCTIONS: readonly string[] = ['to_float', 'to_int', 'to_string']

const VERSION_RE = /^\d+\.\d+\.\d+$/
const RULE_NAME_RE = /^\s*rule\s+"([^"]+)"/m

const DEFAULT_SOURCE = `# 左边写规则，右边看诊断，Ctrl/Cmd + S 提交草稿。
rule "usb_device" {
    version: "1.0.0"
    author: "PACC Security Team"
    severity: medium
    category: "device"
    description: "外设宏设备检测"
    enabled: true

    input {
        event_type: string
        detail: string
    }

    let detail_lower = to_lower(detail)
    let macro_mark = contains(detail_lower, "macro") or contains(detail_lower, "script")
    let confidence = if macro_mark: 0.9 else: 0.6 end

    when:
        event_type == "usb_device"

    then:
        emit_alert(
            type = "usb_device",
            confidence = confidence,
            evidence = {
                "reason": "检测到可疑 USB 外设"
            }
        )
}
`

interface Feedback {
  tone: 'ok' | 'error'
  text: string
}

const FIELD_STYLE = { display: 'grid', gap: 6, flex: '1 1 160px' } as const

export default function RuleEditor() {
  const [source, setSource] = useState(DEFAULT_SOURCE)
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([])
  const [activeLine, setActiveLine] = useState(1)
  const [revealLine, setRevealLine] = useState({ line: 0, seq: 0 })
  const [version, setVersion] = useState('1.0.0')
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  // 版本管理面板自己拉列表，提交草稿后靠换 key 让它重新挂载一次
  const [listKey, setListKey] = useState(0)

  const endpoint = useMemo<PrlClientOptions>(() => ({ baseUrl: PRL_BASE, fetcher: prlFetcher }), [])
  // 规则名以源码里的 rule 声明为准：面板和接口都按它查版本，不能由界面单独填一个
  const ruleName = useMemo(() => RULE_NAME_RE.exec(source)?.[1] ?? '', [source])
  const lineCount = useMemo(() => source.split(/\r?\n/).length, [source])

  const handleDiagnostics = useCallback((next: Diagnostic[]) => setDiagnostics(next), [])

  const handleSelectLine = useCallback((line: number) => {
    setRevealLine((previous) => ({ line, seq: previous.seq + 1 }))
  }, [])

  const submitDraft = useCallback(async () => {
    if (!ruleName) {
      setFeedback({ tone: 'error', text: '源码里没有 rule "名字" 声明，不知道该提交给哪条规则。' })
      return
    }
    const next = version.trim()
    if (!VERSION_RE.test(next)) {
      setFeedback({ tone: 'error', text: `版本号要写成 1.0.0 这种 x.y.z 形式，当前是「${version}」。` })
      return
    }
    setSubmitting(true)
    try {
      // 作者与审批人不在这里填：后端会取当前登录管理员，留空比塞一个假名字可靠
      await createDraftVersion(endpoint, {
        ruleName,
        version: next,
        source,
        author: '',
        note: note.trim() || undefined,
      })
      setFeedback({ tone: 'ok', text: `已提交 ${ruleName} v${next} 的草稿，可以在下方进入灰度。` })
      setListKey((key) => key + 1)
    } catch (caught) {
      // 静态分析不过就不入库，失败原因由后端给，这里原样转述
      setFeedback({ tone: 'error', text: `提交草稿失败：${describePrlError(caught)}` })
    } finally {
      setSubmitting(false)
    }
  }, [endpoint, note, ruleName, source, version])

  return (
    <div className="prl-workbench">
      <p className="prl-hint">
        规则 <span className="prl-mono">{ruleName || '未命名'}</span> · {lineCount} 行 ·{' '}
        {diagnostics.length} 条诊断
      </p>

      <div className="prl-split">
        <div className="prl-split__main">
          <Editor
            value={source}
            onChange={setSource}
            onSave={() => void submitDraft()}
            diagnostics={diagnostics}
            activeLine={activeLine}
            onActiveLineChange={setActiveLine}
            revealLine={revealLine}
            hostFunctions={HOST_FUNCTIONS}
            minLines={26}
          />
        </div>
        <div className="prl-split__side">
          <Linter
            {...endpoint}
            source={source}
            activeLine={activeLine}
            onSelectLine={handleSelectLine}
            hostFunctions={HOST_FUNCTIONS}
            ruleName={ruleName}
            onDiagnostics={handleDiagnostics}
          />
        </div>
      </div>

      <Panel title="提交草稿" subtitle="静态分析不通过就不会入库" index={1}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
          <label style={FIELD_STYLE}>
            <span className="prl-muted">版本号</span>
            <input
              className="prl-input prl-mono"
              value={version}
              onChange={(event) => setVersion(event.target.value)}
              placeholder="1.0.0"
              spellCheck={false}
            />
          </label>
          <label style={{ ...FIELD_STYLE, flex: '2 1 260px' }}>
            <span className="prl-muted">变更说明（可选）</span>
            <input
              className="prl-input"
              value={note}
              onChange={(event) => setNote(event.target.value)}
              placeholder="这次改了什么、为什么改"
            />
          </label>
          <button
            type="button"
            className="prl-btn"
            disabled={submitting}
            onClick={() => void submitDraft()}
          >
            {submitting ? '提交中…' : '提交草稿'}
          </button>
        </div>
        {feedback &&
          (feedback.tone === 'error' ? (
            <InlineError message={feedback.text} />
          ) : (
            <p className="prl-hint">{feedback.text}</p>
          ))}
      </Panel>

      <RuleVersionManager
        key={listKey}
        {...endpoint}
        ruleName={ruleName}
        source={source}
        canaryPercent={1}
      />
    </div>
  )
}