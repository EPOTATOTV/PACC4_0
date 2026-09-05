import { Tag } from 'antd'
import { ticketStatusMeta } from './meta'
import type { SupportTicket } from '../../types'

/** 工单状态标签：管理端与玩家端共用，颜色随 antd 主题自动适配 */
export default function TicketStatusTag({ status }: { status: SupportTicket['status'] }) {
  const m = ticketStatusMeta[status]
  return <Tag color={m?.color}>{m?.text ?? status}</Tag>
}
