import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Empty, Input, Modal, Row, Space, Table, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { ArrowRightOutlined, PlayCircleOutlined } from '@ant-design/icons'
import MetricCard from '../components/MetricCard'
import { api } from '../api/client'
import type { CompetitionOverview, Enrollment, IpCluster, MapBanPickSession, MatchSession, SuspicionFlag } from '../types'

const { Title, Text } = Typography

const kindNames: Record<string, string> = {
  SHARED_ACCOUNT_MULTI_DEVICE: '多设备交替（共享/代练）',
  SHARED_ACCOUNT_MULTI_IP: '多 IP 交替（网络代练/共享）',
  DEVICE_FLAPPING: '设备指纹抖动',
  UNKNOWN: '未知',
}

const statusText: Record<string, string> = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' }
const statusColor: Record<string, string> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'error' }

const bpStatusNames: Record<string, string> = { PENDING: '待开始', ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }
const bpStatusColors: Record<string, string> = { PENDING: 'default', ACTIVE: 'processing', PAUSED: 'warning', COMPLETED: 'success', CANCELLED: 'error' }

export default function Competition() {
  const navigate = useNavigate()
  const [overview, setOverview] = useState<CompetitionOverview | null>(null)
  const [flags, setFlags] = useState<SuspicionFlag[]>([])
  const [enrollments, setEnrollments] = useState<Enrollment[]>([])
  const [selected, setSelected] = useState<React.Key[]>([])
  const [matches, setMatches] = useState<MatchSession[]>([])
  const [bpSessions, setBpSessions] = useState<MapBanPickSession[]>([])
  const [clusters, setClusters] = useState<IpCluster[]>([])
  const [keyword, setKeyword] = useState('')
  const [err, setErr] = useState('')

  const [pteid, setPteid] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [tournamentId, setTournamentId] = useState('')

  // 文本输入弹窗（替换 window.prompt）
  const [promptState, setPromptState] = useState<{
    title: string; label: string; onSubmit: (v: string) => void; initial?: string;
  } | null>(null)
  const [promptVal, setPromptVal] = useState('')

  async function load() {
    try {
      const [ov, fs, es, ms, cs, bps] = await Promise.all([
        api.competition.overview(),
        api.competition.flags(keyword),
        api.competition.enrollments(),
        api.competition.matches(),
        api.competition.ipClusters(),
        api.maps.bpSessions(),
      ])
      setOverview(ov); setFlags(fs); setEnrollments(es); setMatches(ms); setClusters(cs); setBpSessions(bps); setErr('')
    } catch (e) { setErr((e as Error).message) }
  }
  // 刻意仅在挂载时加载一次：load 依赖 keyword（5 个接口），若加入依赖会在每次输入时重载全部数据；
  // 检索仅在回车/按钮时通过 load() 手动触发。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [])

  function ask(title: string, label: string, initial = '', onSubmit: (v: string) => void) {
    setPromptVal(initial)
    setPromptState({ title, label, onSubmit })
  }

  function openReview(f: SuspicionFlag, decision: string) {
    if (f.status !== 'OPEN') return
    ask(`复核（${decision === 'ban' ? '禁赛' : '无异常'}）`, '备注', '', async (comment) => {
      await api.competition.review(f.flagId, decision, comment); load()
    })
  }

  async function enroll() {
    if (!pteid.trim()) return setErr('请填写 PTEID')
    try {
      await api.competition.enroll({ pteid, display_name: displayName, tournament_id: tournamentId })
      setPteid(''); load()
    } catch (e) { setErr((e as Error).message) }
  }

  function openReject(en: Enrollment) {
    ask(`拒绝报名（${en.displayName || en.pteid}）`, '拒绝原因', '', async (note) => {
      await api.competition.approve(en.enrollmentId, false, note); load()
    })
  }

  async function approve(en: Enrollment) {
    try { await api.competition.approve(en.enrollmentId, true, ''); load() }
    catch (e) { setErr((e as Error).message) }
  }

  async function startMatch(en: Enrollment, minutes: number) {
    try {
      await api.competition.startMatch({ pteid: en.pteid, tournament_id: en.tournamentId ?? '', duration_minutes: String(minutes) })
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  function confirmEnd(m: MatchSession) {
    Modal.confirm({
      title: '结束对局',
      content: '结束后该选手本场 token 失效，确认结束？',
      okText: '结束',
      okButtonProps: { danger: true },
      onOk: () => endMatch(m),
    })
  }

  async function endMatch(m: MatchSession) {
    try { await api.competition.endMatch(m.matchId); load() }
    catch (e) { setErr((e as Error).message) }
  }

  function openTagTeam(en: Enrollment) {
    ask('分配队伍名', '队伍名（Discord 式，如 #Blue2）', en.teamName ?? '', async (name) => {
      if (!name.trim()) return
      ask('队伍徽章颜色', '十六进制，如 #58a6ff', en.teamColor ?? '#3fb950', async (color) => {
        await api.competition.setTeam(en.enrollmentId, { team_name: name.trim(), team_color: color.trim() || '#3fb950' }); load()
      })
    })
  }

  async function tagSelected() {
    if (selected.length === 0) return setErr('请先勾选要分队的选手')
    ask(`为选中的 ${selected.length} 人分配队伍名`, '队伍名（如 #Blue2）', '', async (name) => {
      if (!name.trim()) return
      ask('队伍徽章颜色', '十六进制，如 #58a6ff', '#3fb950', async (color) => {
        const r = await api.competition.setTeamBatch({ enrollment_ids: selected as string[], team_name: name.trim(), team_color: color.trim() || '#3fb950' })
        setErr(''); setSelected([]); load()
        message.success(`已为 ${r.updated} 名选手分配队伍 ${name.trim()}`)
      })
    })
  }

  const overviewCards = useMemo(() => {
    if (!overview) return null
    return (
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }} className="pacc-stagger">
        <Col xs={12} sm={8} md={4}>
          <MetricCard label="累计嫌疑" value={overview.total_flags} accent="var(--kpi-red)" />
        </Col>
        {Object.entries(overview.by_kind).map(([k, v]) => (
          <Col key={k} xs={12} sm={8} md={4}>
            <MetricCard label={kindNames[k] ?? k} value={v} accent="var(--kpi-muted)" />
          </Col>
        ))}
      </Row>
    )
  }, [overview])

  const enrollmentCols: TableColumnsType<Enrollment> = [
    { title: '选手', key: 'name', render: (_, en) => <Text strong>{en.displayName || en.pteid}</Text> },
    { title: 'PTEID / 赛事', key: 'pteid', render: (_, en) => <span style={{ fontSize: 12, color: '#8b949e', fontFamily: 'monospace' }}>{en.pteid} · {en.tournamentId || '通用'}</span> },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <Tag color={statusColor[s]}>{statusText[s] ?? s}</Tag> },
    { title: '队伍', key: 'team', width: 130, render: (_, en) => en.teamName ? <Tag style={{ background: en.teamColor ?? '#3fb950', borderColor: 'transparent', color: '#0d1117', fontWeight: 700 }}>#{en.teamName}</Tag> : <Tag>未分队</Tag> },
    { title: '绑定设备', key: 'device', width: 150, render: (_, en) => <span style={{ fontSize: 11, color: '#8b949e' }}>{en.permittedDeviceFingerprint?.slice(0, 16) || '-'}</span> },
    {
      title: '操作', key: 'ops', width: 260,
      render: (_, en) => (
        <Space size={6} wrap>
          <Button size="small" onClick={() => openTagTeam(en)}>队伍</Button>
          {en.status === 'PENDING' && <Button size="small" type="primary" style={{ background: '#3fb950', borderColor: 'transparent' }} onClick={() => approve(en)}>通过</Button>}
          {en.status === 'APPROVED' && <Button size="small" onClick={() => startMatch(en, 240)}>发起对局(4h)</Button>}
          {en.status !== 'REJECTED' && <Button size="small" danger onClick={() => openReject(en)}>拒绝</Button>}
        </Space>
      ),
    },
  ]

  const matchCols: TableColumnsType<MatchSession> = [
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <Text strong>{v}</Text> },
    { title: 'Token（前16位）', dataIndex: 'matchId', render: (v: string) => <Text code style={{ fontSize: 10 }}>{v.slice(0, 16)}…</Text> },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => (s === 'ACTIVE' ? <Tag color="success">进行中</Tag> : <Tag>已结束</Tag>) },
    {
      title: 'BP 选图', key: 'bp', width: 140,
      render: (_, m) => {
        const maps = parseMaps(m.selectedMaps)
        if (!m.bpSessionId) return <Text type="secondary" style={{ fontSize: 12 }}>-</Text>
        return maps.length > 0
          ? <span style={{ fontSize: 12 }}>{maps.map((x) => x.map_name).join(' / ')}</span>
          : <Tag bordered={false} color="warning">BP 关联</Tag>
      },
    },
    { title: '开始', dataIndex: 'startedAt', width: 170, render: (v?: string) => <span style={{ fontSize: 12 }}>{fmt(v)}</span> },
    { title: '过期', dataIndex: 'expiresAt', width: 170, render: (v?: string) => <span style={{ fontSize: 12 }}>{fmt(v)}</span> },
    {
      title: '操作', key: 'op', width: 140,
      render: (_, m) => (
        <Space size={4} wrap>
          {m.status === 'ACTIVE' && <Button size="small" danger onClick={() => confirmEnd(m)}>结束</Button>}
          {m.bpSessionId && <Button size="small" icon={<ArrowRightOutlined />} onClick={() => navigate(`/maps/bp/${m.bpSessionId}`)}>BP</Button>}
        </Space>
      ),
    },
  ]

  const bpCols: TableColumnsType<MapBanPickSession> = [
    { title: '赛制', dataIndex: 'format', width: 64, render: (v: string) => <Tag bordered={false}>{v}</Tag> },
    { title: '对阵', key: 'teams', render: (_, s) => (
        <Space size={6} wrap>
          <Tag color="blue">{s.blueTeamName || '蓝方'}</Tag>
          <Text type="secondary">vs</Text>
          <Tag color="red">{s.redTeamName || '红方'}</Tag>
        </Space>
      ) },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => <Tag color={bpStatusColors[v]}>{bpStatusNames[v] ?? v}</Tag> },
    { title: '进度', key: 'turn', width: 120, render: (_, s) => (
        s.status === 'ACTIVE' ? (
          <Text style={{ fontSize: 12 }}>{s.currentRound}/{s.totalRounds} 轮 · {s.turnIndex} 步</Text>
        ) : s.status === 'COMPLETED' ? (
          <Text type="secondary" style={{ fontSize: 12 }}>{s.totalRounds} 图完成</Text>
        ) : <Text type="secondary" style={{ fontSize: 12 }}>-</Text>
      ) },
    { title: '', key: 'op', width: 90, render: (_, s) => (
        <Button size="small" icon={<PlayCircleOutlined />} onClick={() => navigate(`/maps/bp/${s.bpSessionId}`)}>控制台</Button>
      ) },
  ]

  const ipCols: TableColumnsType<IpCluster> = [
    { title: '来源 IP', dataIndex: 'ip', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '账号数', dataIndex: 'account_count', width: 100, render: (v: number) => <Text strong style={{ color: v >= 3 ? '#ff6b5e' : '#d29922' }}>{v}</Text> },
    { title: '关联账号', dataIndex: 'pteids', render: (v: string[]) => <Text type="secondary" style={{ fontSize: 12 }}>{v.join(' · ')}</Text> },
    { title: '研判', key: 'verdict', width: 160, render: (_, c) => (c.account_count >= 3 ? <Tag color="error">关注：同网多账号</Tag> : <Tag color="warning">提示：同网账号</Tag>) },
  ]

  const flagCols: TableColumnsType<SuspicionFlag> = [
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <Text strong>{v}</Text> },
    { title: '类型', dataIndex: 'kind', render: (k: string, f) => <span>{kindNames[k] ?? k}{f.duringMatch && <Tag color="error" style={{ marginLeft: 6 }}>对局中</Tag>}</span> },
    { title: '权重', dataIndex: 'weight', width: 80, render: (v: number) => <span style={{ color: v >= 70 ? '#ff6b5e' : v >= 50 ? '#d29922' : '#3fb950' }}>{v}</span> },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => (s === 'OPEN' ? <Tag color="error">待复核</Tag> : s === 'ESB' ? <Tag color="purple">ESB</Tag> : <Tag color="success">已复核</Tag>) },
    { title: 'ChainHash', dataIndex: 'chainHash', width: 150, render: (v: string) => <Text code style={{ fontSize: 10, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v}</Text> },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (v?: string) => <span style={{ fontSize: 12 }}>{fmt(v)}</span> },
    {
      title: '复核', key: 'review', width: 180,
      render: (_, f) => f.status === 'OPEN'
        ? <Space size={6} wrap>
            <Button size="small" style={{ background: '#3fb950', borderColor: 'transparent', color: '#0d1117' }} onClick={() => openReview(f, 'clear')}>无异常</Button>
            <Button size="small" danger onClick={() => openReview(f, 'ban')}>禁赛</Button>
          </Space>
        : (f.reviewer ? <span style={{ fontSize: 12, color: '#8b949e' }}>{f.reviewer}</span> : '-'),
    },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>赛事风控</Title>
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      {overviewCards}

      <Card title="参赛门禁（报名/审批）" style={{ marginBottom: 16 }} styles={{ body: { padding: 20 } }}>
        <Space style={{ marginBottom: 16 }} wrap>
          <Input placeholder="PTEID *" value={pteid} onChange={(e) => setPteid(e.target.value)} style={{ width: 200 }} />
          <Input placeholder="选手名称（可选）" value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={{ width: 200 }} />
          <Input placeholder="赛事ID（可选）" value={tournamentId} onChange={(e) => setTournamentId(e.target.value)} style={{ width: 160 }} />
          <Button type="primary" onClick={enroll}>报名</Button>
          {selected.length > 0 && <Text type="secondary" style={{ fontSize: 12 }}>已选 {selected.length} 人</Text>}
          <Button disabled={selected.length === 0} style={{ background: '#3fb950', borderColor: 'transparent', color: '#0d1117' }} onClick={tagSelected}>批量分配队伍</Button>
        </Space>
        <Table<Enrollment>
          rowKey="enrollmentId"
          columns={enrollmentCols}
          dataSource={enrollments}
          rowSelection={{
            selectedRowKeys: selected,
            onChange: (keys) => setSelected(keys),
          }}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          scroll={{ x: 900 }}
          locale={{ emptyText: '暂无报名记录' }}
        />
      </Card>

      <Card title="对局会话（session token 隔离）" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        {matches.length === 0 ? <Empty style={{ margin: '16px 0' }} description="暂无对局会话" /> : (
          <Table<MatchSession> rowKey="matchId" columns={matchCols} dataSource={matches} pagination={false} scroll={{ x: 900 }} />
        )}
      </Card>

      <Card
        title={<Space size={8}><PlayCircleOutlined style={{ color: 'var(--kpi-blue)' }} />地图 BP 会话</Space>}
        style={{ marginBottom: 16 }}
        extra={<Button type="link" size="small" onClick={() => navigate('/maps/bp')}>全部 BP</Button>}
        styles={{ body: { padding: 0 } }}
      >
        {bpSessions.length === 0 ? <Empty style={{ margin: '16px 0' }} description="暂无 BP 会话" /> : (
          <Table<MapBanPickSession> rowKey="bpSessionId" columns={bpCols} dataSource={bpSessions.slice(0, 5)} pagination={false} scroll={{ x: 760 }} />
        )}
      </Card>

      <Card title="IP 聚类（疑似枪手/代练网络）" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        {clusters.length === 0 ? <Empty style={{ margin: '16px 0' }} description="无符合条件的 IP 聚类" /> : (
          <Table<IpCluster> rowKey="ip" columns={ipCols} dataSource={clusters} pagination={false} scroll={{ x: 640 }} />
        )}
      </Card>

      <Card title="共享/代练嫌疑（证据哈希链）" styles={{ body: { padding: 20 } }}>
        <Space style={{ marginBottom: 16 }} wrap>
          <Input placeholder="按 PTEID / 详情检索" value={keyword} onChange={(e) => setKeyword(e.target.value)} onPressEnter={load} style={{ width: 360 }} allowClear />
          <Button onClick={load}>检索</Button>
        </Space>
        <Table<SuspicionFlag> rowKey="flagId" columns={flagCols} dataSource={flags} pagination={{ pageSize: 10, hideOnSinglePage: true }} scroll={{ x: 980 }} locale={{ emptyText: '暂无嫌疑' }} />
      </Card>

      <Modal
        open={!!promptState}
        title={promptState?.title}
        okText="确定"
        cancelText="取消"
        onOk={() => {
          const p = promptState
          setPromptState(null)
          p?.onSubmit(promptVal)
        }}
        onCancel={() => setPromptState(null)}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>{promptState?.label}</Text>
        <Input value={promptVal} onChange={(e) => setPromptVal(e.target.value)} placeholder="输入内容" onPressEnter={() => { const p = promptState; setPromptState(null); p?.onSubmit(promptVal) }} />
      </Modal>
    </div>
  )
}

function fmt(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

/** 解析 MatchSession.selectedMaps（JSON 数组字符串），失败返回空数组。 */
function parseMaps(json?: string): { map_id: string; map_name: string }[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}