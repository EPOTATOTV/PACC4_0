import { useEffect, useState } from 'react'
import { Alert, Button, Card, Checkbox, Col, Form, Input, Modal, Progress, Row, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import MetricCard from '../components/MetricCard'
import { ArrowDownOutlined, ArrowUpOutlined, PlusOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { EnrollmentStats, TournamentNotice, TournamentStage } from '../types'

const { Title, Text } = Typography
const { TextArea } = Input

const kindNames: Record<string, string> = {
  QUALIFIER: '资格赛', GROUP: '小组赛', KNOCKOUT: '淘汰赛', FINAL: '决赛', CUSTOM: '自定义',
}
const statusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', DONE: '已结束' }
const kindColors: Record<string, string> = {
  QUALIFIER: '#58a6ff', GROUP: '#3fb950', KNOCKOUT: '#d29922', FINAL: '#ff3b30', CUSTOM: '#8e44ad',
}
const statusColors: Record<string, string> = { PENDING: 'default', ACTIVE: 'warning', DONE: 'success' }

export default function Tournament() {
  const [tournamentId, setTournamentId] = useState('demo-tournament')
  const [tab, setTab] = useState('stages')
  const [stages, setStages] = useState<TournamentStage[]>([])
  const [notices, setNotices] = useState<TournamentNotice[]>([])
  const [stats, setStats] = useState<EnrollmentStats | null>(null)
  const [err, setErr] = useState('')

  // 新增阶段表单
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState('QUALIFIER')
  const [status, setStatus] = useState('PENDING')
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')
  const [note, setNote] = useState('')
  // 公告表单
  const [nTitle, setNTitle] = useState('')
  const [nContent, setNContent] = useState('')
  const [nPinned, setNPinned] = useState(false)
  // 报名配置
  const [cfgTitle, setCfgTitle] = useState('')
  const [docUrl, setDocUrl] = useState('')
  const [deadline, setDeadline] = useState('')
  const [allowRegister, setAllowRegister] = useState(true)
  // 编辑器
  const [editing, setEditing] = useState<TournamentStage | null>(null)

  async function loadConfig() {
    if (!tournamentId.trim()) return
    try {
      const c = await api.competition.config(tournamentId)
      setCfgTitle(c.title ?? ''); setDocUrl(c.tencentDocUrl ?? '')
      setDeadline(toLocalInput(c.applyDeadline ?? '')); setAllowRegister(c.allowRegister)
    } catch (e) { /* 无配置属正常 */ }
  }
  useEffect(() => { load() }, [])
  useEffect(() => { loadConfig() }, [tournamentId])

  async function saveConfig() {
    try {
      await api.competition.updateConfig({
        tournament_id: tournamentId, title: cfgTitle, tencent_doc_url: docUrl,
        apply_deadline: toIso(deadline, 1), allow_register: String(allowRegister),
      })
      setErr('')
    } catch (e) { setErr((e as Error).message) }
  }

  async function load() {
    if (!tournamentId.trim()) return
    try {
      const [s, n] = await Promise.all([api.competition.stages(tournamentId), api.competition.notices(tournamentId)])
      setStages(s); setNotices(n); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }

  async function loadStats() {
    try { setStats(await api.competition.enrollmentStats()); setErr('') } catch (e) { setErr((e as Error).message) }
  }
  useEffect(() => { if (tab === 'overview') loadStats() }, [tab])

  async function addStage() {
    if (!title.trim()) return setErr('请填写阶段标题')
    try {
      await api.competition.addStage({
        tournament_id: tournamentId, title, kind, status,
        start_time: toIso(startTime, 0), end_time: toIso(endTime, 1), note,
      })
      setTitle(''); setStartTime(''); setEndTime(''); setNote('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function applyStage(s: TournamentStage, patch: Record<string, unknown>) {
    try {
      await api.competition.updateStage(s.stageId, patch)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function move(idx: number, dir: -1 | 1) {
    const to = idx + dir
    if (to < 0 || to >= stages.length) return
    try {
      await api.competition.reorderStage(tournamentId, idx, to)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function toggleStatus(s: TournamentStage) {
    const next = s.status === 'PENDING' ? 'ACTIVE' : s.status === 'ACTIVE' ? 'DONE' : 'PENDING'
    await applyStage(s, { status: next })
  }

  async function delStage(s: TournamentStage) {
    Modal.confirm({
      title: '删除阶段',
      content: `删除阶段「${s.title}」？`,
      okButtonProps: { danger: true },
      onOk: async () => { try { await api.competition.deleteStage(s.stageId); load() } catch (e) { setErr((e as Error).message) } },
    })
  }

  async function publish() {
    if (!nTitle.trim()) return setErr('请填写公告标题')
    try {
      await api.competition.publishNotice({ tournament_id: tournamentId, title: nTitle, content: nContent, pinned: nPinned, operator: 'admin' })
      setNTitle(''); setNContent('')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  function delNotice(n: TournamentNotice) {
    Modal.confirm({
      title: '删除公告',
      content: `删除公告「${n.title}」？`,
      okButtonProps: { danger: true },
      onOk: async () => { try { await api.competition.deleteNotice(n.noticeId); load() } catch (e) { setErr((e as Error).message) } },
    })
  }

  const stageCols: TableColumnsType<TournamentStage> = [
    {
      title: '排序', key: 'move', width: 70,
      render: (_, __, idx) => (
        <Space.Compact size="small">
          <Button size="small" icon={<ArrowUpOutlined />} disabled={idx === 0} onClick={() => move(idx, -1)} />
          <Button size="small" icon={<ArrowDownOutlined />} disabled={idx === stages.length - 1} onClick={() => move(idx, 1)} />
        </Space.Compact>
      ),
    },
    { title: '类别', dataIndex: 'kind', width: 90, render: (k: string) => <Tag color={kindColors[k]}> {kindNames[k] ?? k}</Tag> },
    { title: '阶段', dataIndex: 'title', render: (v: string) => <Text strong>{v}</Text> },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string, s) => <Tag color={statusColors[v]} style={{ cursor: 'pointer' }} onClick={() => toggleStatus(s)}>{statusNames[v] ?? v}</Tag> },
    { title: '时间', key: 'time', width: 230, render: (_, s) => <Text type="secondary" style={{ fontSize: 12 }}>{s.startTime ? fmt(s.startTime) : ''}{s.startTime && s.endTime ? ' → ' : ''}{s.endTime ? fmt(s.endTime) : ''}</Text> },
    { title: '结果', dataIndex: 'resultNote', width: 110, render: (v?: string) => <Text type={v ? 'success' : 'secondary'} style={{ fontSize: 12 }}>{v || '-'}</Text> },
    {
      title: '操作', key: 'ops', width: 120,
      render: (_, s) => (
        <Space size={6} wrap>
          <Button size="small" onClick={() => setEditing(s)}>编辑</Button>
          <Button size="small" danger onClick={() => delStage(s)}>删除</Button>
        </Space>
      ),
    },
  ]

  const teamEntries = stats ? Object.entries(stats.by_team ?? {}).sort((a, b) => b[1] - a[1]) : []
  const teamMax = Math.max(1, ...teamEntries.map(([, n]) => n))

  const tabs = [
    {
      key: 'stages',
      label: '赛程编排',
      children: (
        <>
          <Card title="新增阶段" style={{ marginBottom: 16 }}>
            <Form layout="vertical" onFinish={addStage}>
              <Row gutter={12}>
                <Col xs={24} sm={12} lg={5}><Form.Item label="标题" required><Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="标题 *" /></Form.Item></Col>
                <Col xs={12} sm={6} lg={3}><Form.Item label="类别"><Select value={kind} onChange={setKind} options={Object.entries(kindNames).map(([k, v]) => ({ value: k, label: v }))} /></Form.Item></Col>
                <Col xs={12} sm={6} lg={3}><Form.Item label="状态"><Select value={status} onChange={setStatus} options={Object.entries(statusNames).map(([k, v]) => ({ value: k, label: v }))} /></Form.Item></Col>
                <Col xs={24} sm={12} lg={4}><Form.Item label="开始时间(可选)"><input type="datetime-local" value={startTime} onChange={(e) => setStartTime(e.target.value)} style={{ width: '100%', background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: '5px 10px' }} /></Form.Item></Col>
                <Col xs={24} sm={12} lg={4}><Form.Item label="结束时间(可选)"><input type="datetime-local" value={endTime} onChange={(e) => setEndTime(e.target.value)} style={{ width: '100%', background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: '5px 10px' }} /></Form.Item></Col>
                <Col xs={24} sm={12} lg={4}><Form.Item label="备注(可选)"><Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="备注" /></Form.Item></Col>
                <Col xs={24} lg={1}><Form.Item label=" ">
                  <Button type="primary" htmlType="submit" icon={<PlusOutlined />}>添加</Button>
                </Form.Item></Col>
              </Row>
            </Form>
          </Card>
          <Card styles={{ body: { padding: 0 } }}>
            <Table<TournamentStage>
              rowKey="stageId"
              columns={stageCols}
              dataSource={stages}
              pagination={false}
              scroll={{ x: 800 }}
              locale={{ emptyText: '该届暂无阶段，先添加一个。' }}
            />
          </Card>
        </>
      ),
    },
    {
      key: 'overview',
      label: '可视化',
      children: (
        <>
          {stages.length === 0 ? (
            <Card style={{ color: '#8b949e' }}>加载该届赛程后即可查看可视化。</Card>
          ) : (
            <>
              <Card title="报名与队伍统计" style={{ marginBottom: 16 }}>
                {stats ? (
                  <>
                    <Row gutter={[12, 12]} style={{ marginBottom: 20 }} className="pacc-stagger">
                      <Col xs={12} sm={6}><MetricCard label="已报名" value={stats.by_status.APPROVED ?? 0} accent="var(--kpi-green)" /></Col>
                      <Col xs={12} sm={6}><MetricCard label="待审批" value={stats.by_status.PENDING ?? 0} accent="var(--kpi-amber)" /></Col>
                      <Col xs={12} sm={6}><MetricCard label="已拒绝" value={stats.by_status.REJECTED ?? 0} accent="var(--kpi-red)" /></Col>
                      <Col xs={12} sm={6}><MetricCard label="队伍数" value={Object.keys(stats.by_team ?? {}).length} accent="var(--kpi-blue)" /></Col>
                    </Row>
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 10 }}>各队伍人数</Text>
                    {teamEntries.length === 0 ? (
                      <Text type="secondary" style={{ fontSize: 12 }}>尚未分配队伍。</Text>
                    ) : (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                        {teamEntries.map(([team, n]) => (
                          <div key={team} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                            <span style={{ width: 130, fontSize: 12, color: '#e6edf3', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>#{team}</span>
                            <Progress style={{ flex: 1, margin: 0 }} percent={Math.round((n / teamMax) * 100)} showInfo={false} strokeColor="#58a6ff" />
                            <b style={{ width: 28, textAlign: 'right', fontSize: 12 }}>{n}</b>
                          </div>
                        ))}
                      </div>
                    )}
                  </>
                ) : (
                  <Text type="secondary" style={{ fontSize: 12 }}>加载中…</Text>
                )}
              </Card>

              <Card title="赛程总览（甘特时间轴）" style={{ marginBottom: 16 }}>
                {renderGantt(stages)}
              </Card>

              <Row gutter={[16, 16]}>
                <Col xs={24} lg={12}>
                  <Card title="阶段状态">
                    {['ACTIVE', 'PENDING', 'DONE'].map((s) => {
                      const n = stages.filter((x) => x.status === s).length
                      const pct = Math.round((n / (stages.length || 1)) * 100)
                      return (
                        <div key={s} style={{ marginBottom: 14 }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
                            <Text type="secondary">{statusNames[s]}</Text><Text strong>{n}</Text>
                          </div>
                          <Progress percent={pct} showInfo={false} size={{ height: 8 }} strokeColor={kindColors[s === 'ACTIVE' ? 'FINAL' : s === 'DONE' ? 'QUALIFIER' : 'GROUP']} />
                        </div>
                      )
                    })}
                  </Card>
                </Col>
                <Col xs={24} lg={12}>
                  <Card title="阶段类别分布">
                    {Object.entries(kindNames).map(([k, label]) => {
                      const n = stages.filter((x) => x.kind === k).length
                      const pct = Math.round((n / (stages.length || 1)) * 100)
                      return (
                        <div key={k} style={{ marginBottom: 14 }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
                            <Text type="secondary">{label}</Text><Text strong>{n}</Text>
                          </div>
                          <Progress percent={pct} showInfo={false} size={{ height: 8 }} strokeColor={kindColors[k] ?? '#58a6ff'} />
                        </div>
                      )
                    })}
                  </Card>
                </Col>
              </Row>
            </>
          )}
        </>
      ),
    },
    {
      key: 'notices',
      label: '公告',
      children: (
        <>
          <Card title="发布公告" style={{ marginBottom: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Input placeholder="公告标题 *" value={nTitle} onChange={(e) => setNTitle(e.target.value)} />
              <TextArea placeholder="公告内容（赛制说明 / 对阵 / 提醒 / 成绩公示…）" value={nContent} onChange={(e) => setNContent(e.target.value)} rows={4} />
              <Space size={12} align="center">
                <Checkbox checked={nPinned} onChange={(e) => setNPinned(e.target.checked)}>置顶</Checkbox>
                <Button type="primary" onClick={publish}>发布</Button>
              </Space>
            </Space>
          </Card>
          <Card styles={{ body: { padding: 0 } }}>
            {notices.length === 0 ? (
              <div style={{ padding: 20, color: '#8b949e' }}>暂无公告。</div>
            ) : (
              notices.map((n) => (
                <div key={n.noticeId} style={{ padding: '12px 16px', borderTop: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    {n.pinned && <Tag color="purple">置顶</Tag>}
                    <Text strong>{n.title}</Text>
                    <span style={{ flex: 1 }} />
                    <Text type="secondary" style={{ fontSize: 11 }}>{fmt(n.createdAt)}</Text>
                    <Button size="small" danger onClick={() => delNotice(n)}>删除</Button>
                  </div>
                  {n.content && <div style={{ marginTop: 6, color: '#c9d1d9', fontSize: 13, whiteSpace: 'pre-wrap' }}>{n.content}</div>}
                </div>
              ))
            )}
          </Card>
        </>
      ),
    },
    {
      key: 'register',
      label: '报名设置',
      children: (
        <Card>
          <Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 13 }}>
            选手经腾讯文档收集表填资料 → 本平台绑设备 → 提交申请
          </Text>
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <Input placeholder="赛事名称（公开展示）" value={cfgTitle} onChange={(e) => setCfgTitle(e.target.value)} />
            <Input placeholder="腾讯文档收集表链接 https://..." value={docUrl} onChange={(e) => setDocUrl(e.target.value)} />
            <Space wrap>
              <input type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} style={{ background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: '5px 10px' }} />
              <Tag color={allowRegister ? 'success' : 'default'} style={{ fontSize: 13, padding: '4px 12px', cursor: 'pointer' }} onClick={() => setAllowRegister(!allowRegister)}>
                {allowRegister ? '报名中' : '已截止'}
              </Tag>
            </Space>
            <div>
              <Button type="primary" style={{ background: '#3fb950', borderColor: 'transparent' }} onClick={saveConfig}>保存</Button>
            </div>
          </Space>
        </Card>
      ),
    },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>赛事进程</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Input value={tournamentId} onChange={(e) => setTournamentId(e.target.value)} placeholder="赛事 ID" style={{ width: 220 }} />
        <Button onClick={load}>加载该届赛程</Button>
      </Space>

      <Tabs activeKey={tab} onChange={setTab} items={tabs} />

      <Modal
        title="编辑阶段"
        open={!!editing}
        okText="保存"
        cancelText="取消"
        onCancel={() => setEditing(null)}
        onOk={() => {
          const s = editing
          if (!s) return
          applyStage(s, {
            title: s.title, kind: s.kind, status: s.status,
            start_time: s.startTime, end_time: s.endTime, result_note: s.resultNote, note: s.note,
          })
          setEditing(null)
        }}
      >
        <Form layout="vertical">
          <Form.Item label="标题"><Input value={editing?.title} onChange={(e) => editing && setEditing({ ...editing, title: e.target.value })} /></Form.Item>
          <Row gutter={12}>
            <Col span={12}><Form.Item label="类别"><Select value={editing?.kind} onChange={(v) => editing && setEditing({ ...editing, kind: v })} options={Object.entries(kindNames).map(([k, v]) => ({ value: k, label: v }))} /></Form.Item></Col>
            <Col span={12}><Form.Item label="状态"><Select value={editing?.status} onChange={(v) => editing && setEditing({ ...editing, status: v })} options={Object.entries(statusNames).map(([k, v]) => ({ value: k, label: v }))} /></Form.Item></Col>
            <Col span={12}><Form.Item label="开始时间"><input type="datetime-local" value={editing ? toLocalInput(editing.startTime ?? '') : ''} onChange={(e) => editing && setEditing({ ...editing, startTime: toIso(e.target.value, 0) })} style={{ width: '100%', background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: '5px 10px' }} /></Form.Item></Col>
            <Col span={12}><Form.Item label="结束时间"><input type="datetime-local" value={editing ? toLocalInput(editing.endTime ?? '') : ''} onChange={(e) => editing && setEditing({ ...editing, endTime: toIso(e.target.value, 1) })} style={{ width: '100%', background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: '5px 10px' }} /></Form.Item></Col>
            <Col span={24}><Form.Item label="结果/比分"><Input value={editing?.resultNote} onChange={(e) => editing && setEditing({ ...editing, resultNote: e.target.value })} /></Form.Item></Col>
            <Col span={24}><Form.Item label="备注"><Input value={editing?.note} onChange={(e) => editing && setEditing({ ...editing, note: e.target.value })} /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>
    </div>
  )
}

function toIso(v: string, end: number): string {
  if (!v) return ''
  const d = new Date(v)
  if (isNaN(d.getTime())) return ''
  if (end === 1) d.setHours(23, 59, 59, 0)
  return d.toISOString()
}
function fmt(s: string): string {
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}
function toLocalInput(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

type GanttBar = { s: TournamentStage; leftPct: number; widthPct: number }
function buildGantt(stages: TournamentStage[]): GanttBar[] {
  const n = stages.length
  if (n === 0) return []
  const hasTime = stages.some((s) => !!s.startTime)
  if (!hasTime) {
    const w = 100 / n
    return stages.map((s, i) => ({ s, leftPct: i * w, widthPct: w }))
  }
  const starts = stages.map((s) => (s.startTime ? new Date(s.startTime).getTime() : NaN)).filter((x) => !isNaN(x))
  const ends = stages.map((s) => (s.endTime ? new Date(s.endTime).getTime() : NaN)).filter((x) => !isNaN(x))
  const min = Math.min(...starts)
  const max = ends.length ? Math.max(...ends) : Math.max(...starts)
  const range = max - min || 1
  const step = range / n
  return stages.map((s, i) => {
    const st = s.startTime ? new Date(s.startTime).getTime() : min + i * step
    const en = s.endTime ? new Date(s.endTime).getTime() : Math.min(st + step, max)
    const leftPct = Math.max(0, ((st - min) / range) * 100)
    const right = Math.min(100, ((en - min) / range) * 100)
    return { s, leftPct, widthPct: Math.max(1.5, right - leftPct) }
  })
}

function renderGantt(stages: TournamentStage[]) {
  const bars = buildGantt(stages)
  const W = 860
  const rowH = 40
  const H = Math.max(120, bars.length * rowH + 24)
  return (
    <div style={{ overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ minWidth: 640 }} role="img" aria-label="赛程甘特时间轴">
        {bars.map((b, i) => {
          const c = kindColors[b.s.kind] ?? '#58a6ff'
          const y = i * rowH
          return (
            <g key={b.s.stageId}>
              <text x={6} y={y + 16} fontSize={12} fill="#8b949e">{b.s.title}</text>
              <rect x={W * 0.28} y={y + 4} width={W * 0.68} height={18} rx={4} fill="#101319" opacity={0.35} />
              <rect x={W * (0.28 + (b.leftPct / 100) * 0.68)} y={y + 4} width={W * 0.68 * (b.widthPct / 100)} height={18} rx={4}
                fill={c} opacity={b.s.status === 'ACTIVE' ? 0.95 : b.s.status === 'DONE' ? 0.55 : 0.8}
                stroke={b.s.status === 'ACTIVE' ? '#ffffff' : 'none'} strokeWidth={1} />
              <text x={W * (0.28 + (b.leftPct / 100) * 0.68) + 6} y={y + 17} fontSize={11} fill="#101319" fontWeight={700}>
                {statusNames[b.s.status] ?? b.s.status}
              </text>
            </g>
          )
        })}
      </svg>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginTop: 10 }}>
        {Object.entries(kindNames).map(([k, label]) => (
          <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, color: '#8b949e' }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: kindColors[k] }} />{label}
          </span>
        ))}
      </div>
    </div>
  )
}