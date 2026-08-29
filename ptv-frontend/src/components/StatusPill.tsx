export function StatusPill({ value }: { value: string }) {
  const colorMap: Record<string, string> = {
    PENDING_INSPECT: '#d29922',
    CONFIRMED: '#ff3b30',
    FALSE_POSITIVE: '#3fb950',
    QUEUED: '#8b949e',
    ACTIVE: '#58a6ff',
    DONE: '#3fb950',
    DRAFT: '#8b949e',
    PUBLISHED: '#3fb950',
    GRAY: '#d29922',
    normal: '#3fb950',
    suspicious: '#d29922',
    high_risk: '#ff3b30',
    locked_inspect: '#ff6b5e',
  }
  const color = colorMap[value] ?? '#8b949e'
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '3px 10px',
        borderRadius: 20,
        fontSize: 12,
        fontWeight: 600,
        color: '#0d1117',
        background: color,
      }}
    >
      {value}
    </span>
  )
}