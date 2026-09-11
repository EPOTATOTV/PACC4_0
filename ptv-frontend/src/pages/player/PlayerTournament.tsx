import { useEffect, useState } from 'react'
import { Alert, Avatar, Button, Card, Divider, Empty, Input, Space, Steps, Tag, Typography } from 'antd'
import { LoginOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { PlayerRegisterInfo, TournamentNotice, TournamentStage } from '../../types'

const { Title, Text } = Typography

const kindNames: Record<string, string> = {
  QUALIFIER: '资格赛', GROUP: '小组赛', KNOCKOUT: '淘汰赛', FINAL: '决赛', CUSTOM: '自定义',
}
const stageTag = (s: string) => {
  const map: Record<string, { color: string; text: string }> = {
    PENDING: { color: 'default', text: '待开始' },
    ACTIVE: { color: 'warning', text: '进行中' },
    DONE: { color: 'success', text: '已结束' },
  }
  const m = map[s]
  return <Tag color={m?.color}>{m?.text ?? s}</Tag>
}

export default function PlayerTournament() {
  const [stages, setStages] = useState<TournamentStage[]>([])
  const [notices, setNotices] = useState<TournamentNotice[]>([])
  const [register, setRegister] = useState<PlayerRegisterInfo | null>(null)
  const [fp, setFp] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [err, setErr] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const TOURNAMENT_ID = 'demo-tournament'

  async function loadAll() {
    try {
      const [s, n, r] = await Promise.all([
        api.player.stages(), api.player.notices(), api.player.registerInfo(TOURNAMENT_ID),
      ])
      setStages(s); setNotices(n); setRegister(r); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { loadAll() }, [])

  const st = register?.status
  const enrolled = !!st?.enrolled
  const regStatus = st?.status ?? ''
  const enrollment = register?.status?.enrollment ?? null
  const canSubmit = !!register?.tencent_doc_url && !!fp.trim()
  const active = stages.find((s) => s.status === 'ACTIVE')

  async function submit() {
    if (!register) return
    setSubmitting(true)
    try {
      await api.player.submitRegister({
        tournament_id: register.tournament_id,
        device_fingerprint: fp.trim() || 'demo-device-fingerprint',
        display_name: (displayName.trim() || register.title) ?? '选手',
      })
      setErr(''); await loadAll()
    } catch (e) { setErr((e as Error).message) } finally { setSubmitting(false) }
  }

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>赛事中心</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      {/* 报名引导 */}
      <Card style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <Text strong style={{ flex: 1, fontSize: 15 }}>{register?.title ?? '赛事报名'}</Text>
          {register?.allow_register
            ? <Tag color="success">报名中</Tag>
            : <Tag color="error">已截止</Tag>}
          <Text type="secondary" style={{ fontSize: 12 }}>
            已报名 {register?.submitted ?? 0} 人{register && register.pending > 0 ? ` / 待审批 ${register.pending}` : ''}
          </Text>
        </div>
        {register?.apply_deadline && (
          <Text type="secondary" style={{ fontSize: 12, marginTop: 6 }}>报名截止：{fmt(register.apply_deadline)}</Text>
        )}

        <Divider style={{ margin: '16px 0' }} />

        <Steps
          size="small"
          current={0}
          items={[{ title: '填写报名资料' }, { title: '绑定参赛设备' }, { title: '提交参赛申请' }]}
          style={{ marginBottom: 16 }}
        />

        <div style={{ marginBottom: 14 }}>
          <Text type="secondary" style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
            第 1 步 · 填写报名资料（腾讯文档收集表）
          </Text>
          {register?.tencent_doc_url ? (
            <Button type="primary" ghost href={register.tencent_doc_url} target="_blank" icon={<LoginOutlined />}>
              打开腾讯文档收集表 ↗
            </Button>
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>主办方尚未配置收集表链接。</Text>
          )}
        </div>

        <div>
          <Text type="secondary" style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
            第 2 步 · 绑定参赛设备（对局将以该设备入场）
          </Text>
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <Input placeholder="设备指纹（客户端自动获取，可手动粘贴）" value={fp} onChange={(e) => setFp(e.target.value)} />
            <Input placeholder="参赛昵称（可选）" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </Space>
        </div>

        <div style={{ marginTop: 16 }}>
          <Button
            type="primary"
            onClick={submit}
            disabled={!canSubmit || submitting}
            loading={submitting}
            style={{ background: '#3fb950' }}
          >
            提交参赛申请
          </Button>
          {!canSubmit && (
            <Text type="danger" style={{ marginLeft: 12, fontSize: 12 }}>需存在收集表链接且已填写设备指纹</Text>
          )}
        </div>

        {enrolled && (
          <div style={{ marginTop: 16, padding: '14px 16px', borderRadius: 8, border: '1px solid var(--border)', background: 'var(--panel)' }}>
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>我的报名状态</Text>
            {enrollment?.teamName ? (
              <Space align="center" style={{ marginBottom: 8 }} size={12}>
                <Avatar
                  style={{ background: enrollment.teamColor ?? '#3fb950', color: '#0d1117', fontWeight: 700 }}
                >
                  {(enrollment.teamName[0] || 'T').toUpperCase()}
                </Avatar>
                <div>
                  <Space size={6}>
                    <Text strong>{enrollment.teamName}</Text>
                    <Tag color={enrollment.teamColor ?? '#3fb950'} style={{ color: '#0d1117' }}>队伍成员</Tag>
                  </Space>
                  <div style={{ fontSize: 11, color: '#8b949e', marginTop: 2 }}>
                    {enrollment.displayName || '你'} · 已绑定参赛设备
                  </div>
                </div>
              </Space>
            ) : (
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 6 }}>主办方尚未为你分配队伍。</Text>
            )}
            {regStatus === 'APPROVED' && <div style={{ color: '#3fb950' }}>已通过，你已获得参赛资格。</div>}
            {regStatus === 'PENDING' && <div style={{ color: '#d29922' }}>待审批，请等待主办方审核。</div>}
            {regStatus === 'REJECTED' && <div style={{ color: '#ff3b30' }}>已拒绝{enrollment?.note ? `：${enrollment.note}` : ''}。</div>}
          </div>
        )}
      </Card>

      {/* 我的赛程 */}
      <Card title="我的赛程" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        {stages.length === 0 ? (
          <Empty description="暂无赛程，等待主办方公布。" style={{ margin: '8px 0' }} />
        ) : (
          <div style={{ padding: '8px 16px' }}>
            {stages.map((s) => {
              const isActive = s.status === 'ACTIVE'
              const isDone = s.status === 'DONE'
              return (
                <div key={s.stageId} style={{
                  display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0',
                  borderBottom: '1px solid var(--border)',
                }}>
                  <div
                    style={{
                      width: 6, height: 40, borderRadius: 4, alignSelf: 'stretch',
                      background: isDone ? '#3fb950' : isActive ? '#d29922' : '#ff6b5e',
                    }}
                  />
                  <Tag>{kindNames[s.kind] ?? s.kind}</Tag>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>{s.title}</div>
                    <div style={{ fontSize: 11, color: '#8b949e', marginTop: 2 }}>
                      {s.startTime && <span>{fmt(s.startTime)}</span>}
                      {s.startTime && s.endTime && <span> 至 </span>}
                      {s.endTime && <span>{fmt(s.endTime)}</span>}
                      {s.resultNote && <span style={{ color: '#3fb950' }}> · {s.resultNote}</span>}
                    </div>
                  </div>
                  {stageTag(s.status)}
                </div>
              )
            })}
          </div>
        )}
        {active && (
          <div style={{ fontSize: 13, color: '#d29922', padding: '12px 16px', borderTop: '1px solid var(--border)' }}>
            当前正处于「{active.title}」，请使用已许可设备、凭对局令牌入场。
          </div>
        )}
      </Card>

      {/* 赛事公告 */}
      <Card title="赛事公告" styles={{ body: { padding: 0 } }}>
        {notices.length === 0 ? (
          <Empty description="暂无公告。" style={{ margin: '8px 0' }} />
        ) : (
          notices.map((n) => (
            <div key={n.noticeId} style={{ padding: '14px 20px', borderBottom: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {n.pinned && <Tag color="purple">置顶</Tag>}
                <Text strong>{n.title}</Text>
                <span style={{ flex: 1 }} />
                <Text type="secondary" style={{ fontSize: 11 }}>{fmt(n.createdAt)}</Text>
              </div>
              {n.content && (
                <div style={{ marginTop: 6, color: '#c9d1d9', fontSize: 13, whiteSpace: 'pre-wrap' }}>{n.content}</div>
              )}
            </div>
          ))
        )}
      </Card>
    </div>
  )
}

function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}