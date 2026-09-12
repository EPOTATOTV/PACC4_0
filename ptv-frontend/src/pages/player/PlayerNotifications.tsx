import { useEffect, useState } from 'react'
import { Alert, Badge, Button, Card, Empty, Popconfirm, Space, Tabs, Tag, Typography } from 'antd'
import { DeleteOutlined, ReadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { PlayerNotification } from '../../types'

const { Title, Text } = Typography

const KIND_META: Record<string, { label: string; color: string }> = {
  redscreen: { label: '红屏告警', color: 'error' },
  appeal: { label: '申诉结果', color: 'blue' },
  ticket: { label: '工单回复', color: 'geekblue' },
  system: { label: '系统公告', color: 'gold' },
  activity: { label: '活动通知', color: 'purple' },
  reputation: { label: '信誉分变动', color: 'cyan' },
  device: { label: '登录设备', color: 'warning' },
  match: { label: '赛事信息', color: 'green' },
}

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'redscreen', label: '红屏告警' },
  { key: 'appeal', label: '申诉结果' },
  { key: 'ticket', label: '工单回复' },
  { key: 'system', label: '系统公告' },
  { key: 'activity', label: '活动通知' },
]

/**
 * 通知中心：分类 Tab、批量已读/删除、未读计数。
 * 数据来自 /api/player/notifications 系列，后端未就绪时显示空态。
 */
export default function PlayerNotifications() {
  const [active, setActive] = useState('all')
  const [list, setList] = useState<PlayerNotification[]>([])
  const [unread, setUnread] = useState(0)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const [items, un] = await Promise.all([
        api.player.notifications.list(active === 'all' ? undefined : active),
        api.player.notifications.unreadCount().catch(() => ({ count: 0 })),
      ])
      setList(items)
      setUnread(un.count)
      setErr('')
    } catch (e) {
      // 后端未就绪时显示空态而非报错
      if (!err) setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [active])

  async function markRead(id: string) {
    try {
      await api.player.notifications.read(id)
      setList((p) => p.map((n) => (n.id === id ? { ...n, read: true } : n)))
      setUnread((u) => Math.max(0, u - 1))
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function markAll() {
    try {
      await api.player.notifications.readAll()
      setList((p) => p.map((n) => ({ ...n, read: true })))
      setUnread(0)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function remove(id: string) {
    try {
      await api.player.notifications.remove(id)
      setList((p) => p.filter((n) => n.id !== id))
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>
          通知中心
        </Title>
        <Badge count={unread} overflowCount={99} showZero style={{ marginLeft: 4 }} />
        <Space style={{ marginLeft: 'auto' }}>
          <Button icon={<ReadOutlined />} onClick={markAll} disabled={unread === 0}>全部已读</Button>
        </Space>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card styles={{ body: { padding: 0 } }}>
        <Tabs
          activeKey={active}
          onChange={(k) => setActive(k)}
          items={TABS.map((t) => ({ key: t.key, label: t.label }))}
          tabBarExtraContent={null}
          tabBarStyle={{ padding: '0 16px' }}
        />
        <div style={{ borderTop: '1px solid var(--border)' }}>
          {loading && list.length === 0 ? (
            <Empty style={{ padding: 48 }} description="加载中…" />
          ) : list.length === 0 ? (
            <Empty style={{ padding: 48 }} description="暂无通知" />
          ) : (
            list.map((n) => {
              const meta = KIND_META[n.kind] ?? { label: n.kind, color: 'default' }
              return (
                <div
                  key={n.id}
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 12,
                    padding: '14px 16px',
                    borderBottom: '1px solid var(--border)',
                    background: n.read ? 'transparent' : 'rgba(88,166,255,.05)',
                  }}
                >
                  <Tag color={meta.color} style={{ marginTop: 2, flexShrink: 0 }}>{meta.label}</Tag>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: n.read ? 400 : 700 }}>{n.title}</div>
                    {n.body && <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>{n.body}</Text>}
                    <Text type="secondary" style={{ fontSize: 12 }}>{new Date(n.createdAt).toLocaleString('zh-CN', { hour12: false })}</Text>
                  </div>
                  <Space>
                    {!n.read && <Button type="link" size="small" icon={<ReadOutlined />} onClick={() => markRead(n.id)}>已读</Button>}
                    <Popconfirm title="删除该通知？" onConfirm={() => remove(n.id)}>
                      <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label="删除" />
                    </Popconfirm>
                  </Space>
                </div>
              )
            })
          )}
        </div>
      </Card>
    </div>
  )
}