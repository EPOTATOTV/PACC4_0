import { theme, List, Typography } from 'antd'
import type { ReactNode } from 'react'
import TicketStatusTag from './TicketStatusTag'
import { formatTime, ticketChannelMeta } from './meta'
import type { SupportTicket } from '../../types'

const { Text } = Typography

/**
 * 工单列表：管理端与玩家端共用。
 * 管理端通过 renderActions 注入受理/解决/关闭按钮，玩家端不传即只读。
 */
export default function TicketList({
  tickets,
  loading,
  emptyText = '暂无工单',
  showResolution = false,
  showPteid = false,
  renderActions,
}: {
  tickets: SupportTicket[]
  loading?: boolean
  emptyText?: string
  showResolution?: boolean
  showPteid?: boolean
  renderActions?: (t: SupportTicket) => ReactNode
}) {
  const { token } = theme.useToken()
  return (
    <List
      loading={loading}
      locale={{ emptyText }}
      dataSource={tickets}
      renderItem={(t) => (
        <List.Item
          key={t.ticketId}
          style={{ padding: '14px 20px' }}
          actions={
            renderActions
              ? [<div key="ops" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{renderActions(t)}</div>]
              : [<Text type="secondary" key="time" style={{ fontSize: 12 }}>{formatTime(t.createdAt)}</Text>]
          }
        >
          <List.Item.Meta
            title={
              <span>
                {t.subject}
                <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                  ({ticketChannelMeta[t.channel]?.text ?? t.channel})
                </Text>
                {showPteid && t.pteid ? (
                  <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                    {t.pteid}
                  </Text>
                ) : null}
                <span style={{ marginLeft: 12 }}>
                  <TicketStatusTag status={t.status} />
                </span>
              </span>
            }
            description={
              <div>
                <Text type="secondary">{t.body}</Text>
                {showResolution && t.resolution ? (
                  <div style={{ color: token.colorSuccess, fontSize: 13, marginTop: 6 }}>
                    处理结果：{t.resolution}
                  </div>
                ) : null}
              </div>
            }
          />
        </List.Item>
      )}
    />
  )
}
