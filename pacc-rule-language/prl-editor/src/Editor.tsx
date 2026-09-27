/**
 * PRL 源码编辑器。
 *
 * 自研实现，不引 Monaco / CodeMirror：这个包要被管理端单独替换，且要能直接吃到现成的深色主题。
 * 结构是「高亮层 <pre> + 透明文本域 <textarea> 叠加」，文本域负责编辑与光标，<pre> 负责着色，
 * 叠加层的高度由行数决定，因此不需要横向滚动同步。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, ChangeEvent, UIEvent } from 'react'
import {
  PRL_INDENT,
  extractDeclarations,
  getPrlCompletions,
  identifierPrefixAt,
  tokenizeLine,
} from './prlLanguage'
import type { PrlCompletionCandidate, PrlToken } from './prlLanguage'
import type { Diagnostic } from './types'

const LINE_HEIGHT = 22
const PAD_X = 14
const PAD_Y = 10
/** 拿不到 canvas 上下文时的兜底字符宽度（仅影响候选框横向位置）。 */
const FALLBACK_CHAR_WIDTH = 8.4

export interface EditorProps {
  value: string
  onChange: (value: string) => void
  /** Ctrl/Cmd + S 触发。 */
  onSave?: (value: string) => void
  readOnly?: boolean
  placeholder?: string
  /** 诊断标记，画在行号槽上。 */
  diagnostics?: readonly Diagnostic[]
  /** 外部指定的当前行。 */
  activeLine?: number
  onActiveLineChange?: (line: number) => void
  /** 跳转请求：seq 变化就跳一次（Linter 点击问题时用）。 */
  revealLine?: { line: number; seq: number }
  /** 宿主额外注册的函数名，参与自动补全与未知函数判断。 */
  hostFunctions?: readonly string[]
  /** 最小高度（行数）。 */
  minLines?: number
  className?: string
}

interface CompletionState {
  items: PrlCompletionCandidate[]
  index: number
  /** 待替换区间 [start, end)。 */
  start: number
  end: number
  left: number
  top: number
  placeAbove: boolean
}

const COMPLETION_MAX = 60
const COMPLETION_LIMIT = 12

/** 落在字符串或注释里的光标不弹补全。 */
function caretInsideLiteral(tokens: readonly PrlToken[], caret: number): boolean {
  for (const token of tokens) {
    if ((token.type === 'string' || token.type === 'comment') && caret > token.start && caret <= token.end) {
      return true
    }
  }
  return false
}

function lineStartIndent(value: string, position: number): { indent: string; lineStart: number; lineEnd: number } {
  const lineStart = value.lastIndexOf('\n', position - 1) + 1
  let lineEnd = value.indexOf('\n', position)
  if (lineEnd === -1) {
    lineEnd = value.length
  }
  const line = value.slice(lineStart, lineEnd)
  const indent = /^[ \t]*/.exec(line)?.[0] ?? ''
  return { indent, lineStart, lineEnd }
}

export function Editor({
  value,
  onChange,
  onSave,
  readOnly = false,
  placeholder = '在此编写 PRL 规则…',
  diagnostics,
  activeLine,
  onActiveLineChange,
  revealLine,
  hostFunctions,
  minLines = 12,
  className,
}: EditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const scrollerRef = useRef<HTMLDivElement | null>(null)
  const gutterRef = useRef<HTMLDivElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const pendingSelectionRef = useRef<{ start: number; end: number } | null>(null)

  const [internalLine, setInternalLine] = useState(1)
  const [completion, setCompletion] = useState<CompletionState | null>(null)

  const lines = useMemo(() => value.split(/\r?\n/), [value])
  const tokenLines = useMemo(() => lines.map((line) => tokenizeLine(line)), [lines])
  const declarations = useMemo(() => extractDeclarations(value), [value])
  const digits = String(lines.length).length
  const currentLine = activeLine ?? internalLine

  const markersByLine = useMemo(() => {
    const map = new Map<number, 'error' | 'warning' | 'info'>()
    for (const item of diagnostics ?? []) {
      const previous = map.get(item.line)
      if (previous === 'error') {
        continue
      }
      if (previous === 'warning' && item.severity === 'info') {
        continue
      }
      map.set(item.line, item.severity)
    }
    return map
  }, [diagnostics])

  const codeStyle: CSSProperties = {
    position: 'relative',
    minHeight: `${minLines * LINE_HEIGHT + PAD_Y * 2}px`,
  }
  const textStyle: CSSProperties = {
    lineHeight: `${LINE_HEIGHT}px`,
    padding: `${PAD_Y}px ${PAD_X}px`,
  }

  // 提交后把光标放回指定位置：用 ref 记录，避免在 effect 里 setState
  useEffect(() => {
    const pending = pendingSelectionRef.current
    if (!pending) {
      return
    }
    pendingSelectionRef.current = null
    const element = textareaRef.current
    if (element) {
      element.focus()
      element.setSelectionRange(pending.start, pending.end)
    }
  })

  const apply = useCallback(
    (nextValue: string, start: number, end: number = start) => {
      pendingSelectionRef.current = { start, end }
      onChange(nextValue)
    },
    [onChange],
  )

  const measureWidth = useCallback((text: string): number => {
    const element = textareaRef.current
    if (!element) {
      return text.length * FALLBACK_CHAR_WIDTH
    }
    if (!canvasRef.current) {
      canvasRef.current = document.createElement('canvas')
    }
    const context = canvasRef.current.getContext('2d')
    if (!context) {
      return text.length * FALLBACK_CHAR_WIDTH
    }
    const computed = window.getComputedStyle(element)
    context.font = `${computed.fontSize} ${computed.fontFamily}`
    return context.measureText(text).width
  }, [])

  const updateCompletion = useCallback(
    (element: HTMLTextAreaElement, nextValue: string) => {
      if (readOnly) {
        return
      }
      const caret = element.selectionStart
      const { prefix, start } = identifierPrefixAt(nextValue, caret)
      const lineStart = nextValue.lastIndexOf('\n', caret - 1) + 1
      const lineEndRaw = nextValue.indexOf('\n', caret)
      const lineText = nextValue.slice(lineStart, lineEndRaw === -1 ? nextValue.length : lineEndRaw)
      if (prefix.length === 0 || caretInsideLiteral(tokenizeLine(lineText), caret - lineStart)) {
        setCompletion(null)
        return
      }
      const items = getPrlCompletions({
        prefix,
        declarations,
        hostFunctions,
        limit: COMPLETION_MAX,
      }).slice(0, COMPLETION_LIMIT)
      if (items.length === 0) {
        setCompletion(null)
        return
      }
      const lineIndex = nextValue.slice(0, caret).split('\n').length
      const width = measureWidth(nextValue.slice(lineStart, caret))
      const contentHeight = lines.length * LINE_HEIGHT + PAD_Y * 2
      const placeAbove = (lineIndex - 1) * LINE_HEIGHT + 220 > contentHeight
      setCompletion((previous) => ({
        items,
        index:
          previous && previous.items[previous.index]?.label === items[previous.index]?.label
            ? Math.min(previous.index, items.length - 1)
            : 0,
        start,
        end: caret,
        left: PAD_X + width,
        top: placeAbove
          ? Math.max(PAD_Y, (lineIndex - 2) * LINE_HEIGHT + PAD_Y)
          : lineIndex * LINE_HEIGHT + PAD_Y,
        placeAbove,
      }))
    },
    [declarations, hostFunctions, lines.length, measureWidth, readOnly],
  )

  const commitCompletion = useCallback(
    (candidate: PrlCompletionCandidate) => {
      const element = textareaRef.current
      if (!element || !completion) {
        return
      }
      const nextChar = value.slice(completion.end, completion.end + 1)
      const needsCall = candidate.kind === 'function' && nextChar !== '('
      const insert = needsCall ? `${candidate.label}()` : candidate.label
      const nextValue = `${value.slice(0, completion.start)}${insert}${value.slice(completion.end)}`
      const caret = completion.start + candidate.label.length + (needsCall ? 1 : 0)
      setCompletion(null)
      apply(nextValue, caret)
    },
    [apply, completion, value],
  )

  const handleScroll = useCallback((event: UIEvent<HTMLElement>) => {
    if (gutterRef.current) {
      gutterRef.current.style.transform = `translateX(${event.currentTarget.scrollLeft}px)`
    }
  }, [])

  const syncActiveLine = useCallback(
    (element: HTMLTextAreaElement) => {
      const line = element.value.slice(0, element.selectionStart).split('\n').length
      setInternalLine(line)
      onActiveLineChange?.(line)
    },
    [onActiveLineChange],
  )

  useEffect(() => {
    if (!revealLine || revealLine.line <= 0) {
      return
    }
    const element = textareaRef.current
    const scroller = scrollerRef.current
    if (!element) {
      return
    }
    const target = Math.min(revealLine.line, lines.length)
    let offset = 0
    for (let i = 0; i < target - 1; i += 1) {
      offset += lines[i].length + 1
    }
    element.focus()
    element.setSelectionRange(offset, offset + lines[target - 1].length)
    setInternalLine(target)
    onActiveLineChange?.(target)
    if (scroller) {
      const top = (target - 1) * LINE_HEIGHT
      if (top < scroller.scrollTop || top > scroller.scrollTop + scroller.clientHeight - LINE_HEIGHT * 2) {
        scroller.scrollTop = Math.max(0, top - scroller.clientHeight / 3)
      }
    }
  }, [revealLine, lines, onActiveLineChange])

  const handleChange = useCallback(
    (event: ChangeEvent<HTMLTextAreaElement>) => {
      const element = event.currentTarget
      onChange(element.value)
      syncActiveLine(element)
      updateCompletion(element, element.value)
    },
    [onChange, syncActiveLine, updateCompletion],
  )

  const handleKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLTextAreaElement>) => {
      const element = event.currentTarget
      const start = element.selectionStart
      const end = element.selectionEnd
      const text = element.value

      if (event.nativeEvent.isComposing) {
        return
      }

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault()
        onSave?.(text)
        return
      }

      if (completion) {
        if (event.key === 'ArrowDown') {
          event.preventDefault()
          setCompletion({ ...completion, index: (completion.index + 1) % completion.items.length })
          return
        }
        if (event.key === 'ArrowUp') {
          event.preventDefault()
          setCompletion({ ...completion, index: (completion.index - 1 + completion.items.length) % completion.items.length })
          return
        }
        if (event.key === 'Enter' || event.key === 'Tab') {
          event.preventDefault()
          const candidate = completion.items[completion.index]
          if (candidate) {
            commitCompletion(candidate)
          }
          return
        }
        if (event.key === 'Escape') {
          event.preventDefault()
          setCompletion(null)
          return
        }
      }

      if (event.key === 'Tab') {
        event.preventDefault()
        if (event.shiftKey) {
          const { lineStart, lineEnd } = lineStartIndent(text, start)
          const lineText = text.slice(lineStart, lineEnd)
          const removed = /^( {1,4}|\t)/.exec(lineText)?.[0]?.length ?? 0
          if (removed > 0) {
            const nextValue = `${text.slice(0, lineStart)}${lineText.slice(removed)}${text.slice(lineEnd)}`
            apply(nextValue, Math.max(lineStart, start - removed), Math.max(lineStart, end - removed))
          }
          return
        }
        apply(`${text.slice(0, start)}${PRL_INDENT}${text.slice(end)}`, start + PRL_INDENT.length)
        return
      }

      if (event.key === 'Enter') {
        event.preventDefault()
        const { indent, lineStart } = lineStartIndent(text, start)
        const lineBefore = text.slice(lineStart, start)
        const opensBlock = /[:{]\s*$/.test(lineBefore)
        const nextIndent = opensBlock ? `${indent}${PRL_INDENT}` : indent
        const insert = `\n${nextIndent}`
        apply(`${text.slice(0, start)}${insert}${text.slice(end)}`, start + insert.length)
        return
      }

      if (!readOnly && (event.key === '(' || event.key === '[' || event.key === '{')) {
        event.preventDefault()
        const pair = `${event.key}${event.key === '(' ? ')' : event.key === '[' ? ']' : '}'}`
        const selected = text.slice(start, end)
        const nextValue = `${text.slice(0, start)}${pair[0]}${selected}${pair[1]}${text.slice(end)}`
        apply(nextValue, start + 1, start + 1 + selected.length)
        return
      }

      if (!readOnly && (event.key === '"' || event.key === "'")) {
        event.preventDefault()
        const selected = text.slice(start, end)
        const nextValue = `${text.slice(0, start)}${event.key}${selected}${event.key}${text.slice(end)}`
        apply(nextValue, start + 1, start + 1 + selected.length)
        return
      }

      if (!readOnly && (event.key === ')' || event.key === ']' || event.key === '}') && text[start] === event.key) {
        event.preventDefault()
        apply(text, start + 1)
        return
      }

      if (!readOnly && event.key === 'Backspace' && start === end && start > 0) {
        const before = text[start - 1]
        const after = text[start]
        const paired =
          (before === '(' && after === ')') ||
          (before === '[' && after === ']') ||
          (before === '{' && after === '}') ||
          (before === '"' && after === '"') ||
          (before === "'" && after === "'")
        if (paired) {
          event.preventDefault()
          apply(`${text.slice(0, start - 1)}${text.slice(start + 1)}`, start - 1)
        }
      }
    },
    [apply, commitCompletion, completion, onSave, readOnly],
  )

  const handleSelect = useCallback(() => {
    const element = textareaRef.current
    if (element) {
      syncActiveLine(element)
    }
  }, [syncActiveLine])

  const handleBlur = useCallback(() => {
    setCompletion(null)
  }, [])

  const gutterWidth = `${Math.max(digits, 2) + 2}ch`

  return (
    <div className={`prl-editor${className ? ` ${className}` : ''}${readOnly ? ' prl-editor--readonly' : ''}`}>
      <div className="prl-editor__scroller" ref={scrollerRef} onScroll={handleScroll}>
        <div className="prl-editor__body">
          <div className="prl-editor__gutter" ref={gutterRef} style={{ width: gutterWidth, paddingTop: PAD_Y }}>
            {lines.map((_, index) => {
              const lineNo = index + 1
              const marker = markersByLine.get(lineNo)
              return (
                <div
                  key={lineNo}
                  className={`prl-editor__ln${lineNo === currentLine ? ' prl-editor__ln--active' : ''}`}
                  style={{ height: LINE_HEIGHT, lineHeight: `${LINE_HEIGHT}px` }}
                >
                  {marker && <span className={`prl-editor__marker prl-editor__marker--${marker}`} aria-hidden="true" />}
                  {lineNo}
                </div>
              )
            })}
          </div>
          <div className="prl-editor__code" style={codeStyle}>
            <div
              className="prl-editor__active-line"
              style={{ top: (currentLine - 1) * LINE_HEIGHT + PAD_Y, height: LINE_HEIGHT }}
              aria-hidden="true"
            />
            <pre className="prl-editor__highlight" style={textStyle} aria-hidden="true">
              {tokenLines.map((tokens, index) => (
                <div key={index} className="prl-editor__row" style={{ height: LINE_HEIGHT }}>
                  {tokens.map((token, tokenIndex) => (
                    <span key={tokenIndex} className={`prl-tok prl-tok--${token.type}`}>
                      {token.value}
                    </span>
                  ))}
                </div>
              ))}
            </pre>
            <textarea
              ref={textareaRef}
              className="prl-editor__input"
              style={textStyle}
              value={value}
              onChange={handleChange}
              onKeyDown={handleKeyDown}
              onKeyUp={handleSelect}
              onClick={handleSelect}
              onSelect={handleSelect}
              onBlur={handleBlur}
              onScroll={handleScroll}
              readOnly={readOnly}
              placeholder={placeholder}
              spellCheck={false}
              autoCorrect="off"
              autoCapitalize="off"
              wrap="off"
              aria-label="PRL 源码"
            />
            {completion && (
              <ul
                className={`prl-ac${completion.placeAbove ? ' prl-ac--above' : ''}`}
                style={{ left: completion.left, top: completion.top }}
              >
                {completion.items.map((item, index) => (
                  <li key={`${item.kind}-${item.label}`}>
                    <button
                      type="button"
                      className={`prl-ac__item${index === completion.index ? ' prl-ac__item--on' : ''}`}
                      onMouseDown={(event) => {
                        event.preventDefault()
                        commitCompletion(item)
                      }}
                    >
                      <span className="prl-ac__label">{item.label}</span>
                      <span className="prl-ac__detail prl-mono">{item.detail}</span>
                    </button>
                  </li>
                ))}
                <li className="prl-ac__hint">
                  {completion.items[completion.index]?.description ?? ''}
                  <span className="prl-muted"> · ↑↓ 选择 · Enter/Tab 确认 · Esc 关闭</span>
                </li>
              </ul>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}