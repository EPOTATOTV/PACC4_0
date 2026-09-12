import { useEffect, useState } from 'react'
import { Alert, Button, Card, Typography, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import TicketForm from '../../components/ticket/TicketForm'
import TicketList from '../../components/ticket/TicketList'
import type { TicketFormValues } from '../../components/ticket/TicketForm'
import type { SupportTicket } from '../../types'

const { Title } = Typography

export default function PlayerTickets() {
  const [tickets, setTickets] = useState<SupportTicket[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const navigate = useNavigate()

  async function load() {
    setLoading(true)
    try {
      setTickets(await api.player.tickets())
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [])

  async function submit(v: TicketFormValues) {
    if (!v.subject || !v.subject.trim()) return
    setSubmitting(true)
    try {
      await api.player.submitTicket({ channel: v.channel, subject: v.subject, body: v.body ?? '' })
      message.success('工单已提交，客服将尽快处理')
      load()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>客服工单</Title>
        <div style={{ flex: 1 }} />
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card title="提交工单" style={{ marginBottom: 16 }} styles={{ body: { padding: 20 } }}>
        <TicketForm
          onSubmit={submit}
          submitting={submitting}
          hint="工单会在后台按渠道分类统一处理"
        />
      </Card>

      <Card title="我的工单" styles={{ body: { padding: 0 } }}>
        <TicketList
          tickets={tickets}
          loading={loading}
          showResolution
          renderActions={(t) => (
            <Button type="link" size="small" onClick={() => navigate(`/portal/tickets/${t.ticketId}`)}>
              查看详情
            </Button>
          )}
        />
      </Card>
    </div>
  )
}
