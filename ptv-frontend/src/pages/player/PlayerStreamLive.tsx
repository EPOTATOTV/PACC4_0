import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Empty, Space, Tag, Typography } from 'antd'
import { PlayCircleOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { Broadcast } from '../../types'
import { useI18n } from '../../i18n'

const { Title, Text } = Typography

/**
 * 玩家端赛事直播转播页：展示管理端开启的 B 站直播间。
 * 优先 iframe 内嵌播放；因 B 站可能 X-Frame-Options 拒绝内嵌，加载失败时
 * 降级为"进直播间"新标签按钮。
 */
export default function PlayerStreamLive() {
  const { t } = useI18n()
  const [rows, setRows] = useState<Broadcast[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')
  const [failed, setFailed] = useState<Record<string, boolean>>({})

  const load = useCallback(() => {
    setLoading(true)
    api.player
      .liveStreams()
      .then((d) => setRows(Array.isArray(d) ? d : []))
      .catch((e) => setErr(String((e as Error)?.message || e)))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  const openExternal = (b: Broadcast) => {
    window.open(`https://live.bilibili.com/${b.bilibili_live_id}`, '_blank', 'noopener')
  }

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>{t('player.streamLive.title')}</Title>
      <Text type="secondary" style={{ fontSize: 12 }}>{t('player.streamLive.desc')}</Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable onClose={() => setErr('')} />}

      {!loading && rows.length === 0 && (
        <Card style={{ marginTop: 20 }}>
          <Empty description={t('player.streamLive.empty')} />
        </Card>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))', gap: 16, marginTop: 20 }} className="pacc-stagger">
        {rows.map((b) => (
          <Card
            key={b.id}
            title={
              <Space size={8}>
                <VideoCameraOutlined />
                <span>{b.title}</span>
                {b.live ? <Tag color="success">{t('player.streamLive.live')}</Tag> : <Tag>{t('player.streamLive.offline')}</Tag>}
              </Space>
            }
            extra={
              <Button type="link" icon={<PlayCircleOutlined />} onClick={() => openExternal(b)}>
                {t('player.streamLive.enter')}
              </Button>
            }
          >
            {b.description && (
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 12 }}>
                {b.description}
              </Text>
            )}
            {failed[b.id] ? (
              <div
                style={{
                  height: 320, borderRadius: 8,
                  border: '1px solid var(--border)',
                  background: 'var(--panel)',
                  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12,
                }}
              >
                <Text type="secondary">{t('player.streamLive.offline')}</Text>
                <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => openExternal(b)}>
                  {t('player.streamLive.enter')}
                </Button>
              </div>
            ) : (
              <iframe
                title={b.title}
                src={`https://live.bilibili.com/${b.bilibili_live_id}`}
                style={{ width: '100%', height: 320, border: 0, borderRadius: 8, background: '#000' }}
                loading="lazy"
                referrerPolicy="no-referrer"
                allow="fullscreen; autoplay; encrypted-media"
                onError={() => setFailed((p) => ({ ...p, [b.id]: true }))}
              />
            )}
          </Card>
        ))}
      </div>
    </div>
  )
}