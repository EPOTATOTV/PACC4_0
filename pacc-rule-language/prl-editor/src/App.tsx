/**
 * 演示页：把五个组件放在一起看效果，同时也是管理端接入方式的示例。
 * 这里不做任何业务判断，只负责把 source / diagnostics / 端点配置在组件之间传。
 */
import { useCallback, useMemo, useState } from 'react'
import { Editor } from './Editor'
import { Linter } from './Linter'
import { Debugger } from './Debugger'
import { Profiler } from './Profiler'
import { RuleVersionManager } from './RuleVersionManager'
import { PRL_API_BASE } from './api'
import type { Diagnostic } from './types'
import { Badge } from './ui'

const DEFAULT_SOURCE = `# kill_aura：击杀光环（高命中率 + 快速转火 + 非人类点击模式）
# 与 pacc-rule-language/examples/kill_aura.prl 等价的最小版本，供 demo 用。

rule "kill_aura_detection" {
    version: "1.2.0"
    author: "PACC Security Team"
    severity: high
    category: "combat"
    cooldown: 300s
    enabled: true
    description: "命中率与转火速率同时异常，且点击模式不像人"

    input {
        player: PlayerContext
        attack_events: list[AttackEvent]
        system_state: SystemState
    }

    let attack_rate = rate(attack_events, 1s)
    let hit_accuracy = accuracy(attack_events)
    let target_switch_rate = rate(filter(attack_events, e -> e.target_changed), 1s)
    let click_pattern = analyze_click_pattern(attack_events)
    let confidence = 0.92

    when:
        attack_rate > 8 per_second
        AND hit_accuracy > 0.95
        AND target_switch_rate > 3 per_second
        AND NOT click_pattern.is_human_like()
        AND NOT player.is_trusted()
        AND system_state.network_stable()

    then:
        emit_alert(
            type = "KILL_AURA",
            confidence = confidence,
            evidence = {
                "attack_rate": attack_rate,
                "hit_accuracy": hit_accuracy,
                "target_switch_rate": target_switch_rate
            }
        )
        record_evidence("attack_timeline", last_n(attack_events, 100))
        log(level = "warn", message = f"kill_aura 命中: {player.name}")
        if confidence > 0.9:
            trigger_redscreen(reason = "kill_aura_detected")
        end
}
`

/** 管理端按后端下发的宿主函数白名单填充这里；demo 里没有额外宿主函数。 */
const HOST_FUNCTIONS: readonly string[] = []

type TabId = 'editor' | 'debug' | 'profile' | 'versions'

const TABS: readonly { id: TabId; label: string }[] = [
  { id: 'editor', label: '编辑器与检查' },
  { id: 'debug', label: '调试' },
  { id: 'profile', label: '性能' },
  { id: 'versions', label: '版本管理' },
]

export function App() {
  const [source, setSource] = useState(DEFAULT_SOURCE)
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([])
  const [activeLine, setActiveLine] = useState(1)
  const [revealLine, setRevealLine] = useState({ line: 0, seq: 0 })
  const [tab, setTab] = useState<TabId>('editor')
  const [useBackend, setUseBackend] = useState(false)
  const [savedNote, setSavedNote] = useState('Ctrl+S 会触发 onSave')

  const endpoint = useBackend ? { baseUrl: PRL_API_BASE } : {}

  const ruleName = useMemo(() => /^\s*rule\s+"([^"]+)"/m.exec(source)?.[1] ?? '', [source])
  const lineCount = useMemo(() => source.split(/\r?\n/).length, [source])

  const handleDiagnostics = useCallback((next: Diagnostic[]) => {
    setDiagnostics(next)
  }, [])

  const handleSave = useCallback((value: string) => {
    setSavedNote(`Ctrl+S 已触发 onSave（${value.split(/\r?\n/).length} 行）——演示页只做本地记录，没有接保存接口`)
  }, [])

  const handleSelectLine = useCallback((line: number) => {
    setTab('editor')
    setRevealLine((previous) => ({ line, seq: previous.seq + 1 }))
  }, [])

  return (
    <div className="prl-app">
      <header className="prl-topbar">
        <div className="prl-topbar__brand">
          <h1 className="prl-topbar__title">PRL 规则工作台</h1>
          <p className="prl-topbar__subtitle">本地检查 + 引擎分析 + 调试 + 性能 + 版本，全在管理端完成</p>
        </div>
        <div className="prl-topbar__meta">
          <Badge tone={ruleName ? 'gold' : 'muted'}>{ruleName || '未命名规则'}</Badge>
          <Badge tone="neutral">{lineCount} 行</Badge>
          <Badge tone={diagnostics.length > 0 ? 'warning' : 'ok'}>
            {diagnostics.length} 条诊断
          </Badge>
          <label className="prl-switch">
            <input type="checkbox" checked={useBackend} onChange={(event) => setUseBackend(event.target.checked)} />
            接入 /api/prl
          </label>
        </div>
      </header>

      <nav className="prl-tabs">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`prl-tab${tab === item.id ? ' prl-tab--on' : ''}`}
            onClick={() => setTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </nav>

      {tab === 'editor' && (
        <>
          <p className="prl-hint">{savedNote}</p>
          <div className="prl-split">
            <div className="prl-split__main">
              <Editor
                value={source}
                onChange={setSource}
                onSave={handleSave}
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
        </>
      )}

      {tab === 'debug' && (
        <Debugger {...endpoint} source={source} ruleName={ruleName} ruleVersion="1.2.0" />
      )}

      {tab === 'profile' && <Profiler {...endpoint} ruleName={ruleName} ruleVersion="1.2.0" window="1h" />}

      {tab === 'versions' && (
        <RuleVersionManager
          {...endpoint}
          ruleName={ruleName}
          source={source}
          author="pacc-dev"
          approver="sec-lead"
        />
      )}
    </div>
  )
}