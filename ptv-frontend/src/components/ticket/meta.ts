/**
 * 工单中心文案与状态的单一事实源（MyEC frontend-common 形态）。
 * 文案 keyed 化，后续加 `en` 字典即可扩展多语言，不需要改组件。
 */
import type { SupportTicket } from '../../types'

export const ticketStatusMeta: Record<
  SupportTicket['status'],
  { color: string; text: string }
> = {
  open: { color: 'processing', text: '等待处理' },
  in_progress: { color: 'warning', text: '处理中' },
  resolved: { color: 'success', text: '已解决' },
  closed: { color: 'default', text: '已关闭' },
}

export const ticketChannelMeta: Record<string, { text: string }> = {
  ticket: { text: '站内工单' },
  email: { text: '邮件' },
  qq: { text: 'QQ' },
  discord: { text: 'Discord' },
  admin: { text: '管理员' },
}

export const ticketChannels = Object.entries(ticketChannelMeta).map(([value, m]) => ({
  value,
  label: m.text,
}))

/** 日期显示：非法值回退原样 */
export function formatTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}
