import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Col, Descriptions, Empty, Input, List, Progress, Row, Select, Space, Table, Tag, Tooltip,
} from 'antd'
import type { TableColumnsType } from 'antd'
import type { EChartsOption } from 'echarts'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import PageHeader from '../../components/PageHeader'
import EChart from '../../components/EChart'
import { api } from '../../api/client'
import type {
  AdminPlayerDetail, CheatRecord, DeviceRecord, V52BehaviorProfile, V52HighRiskPlayer, V52ReputationDetail,
  V53Trend,
} from '../../types'
import { useScrollReveal, useTableRowReveal } from '../../hooks/useGSAP'

const LEVEL_META: Record<string, { label: string; color: string }> = {
  TRUSTED: { label: '信任', color: 'green' },
  NORMAL: { label: '正常', color: 'cyan' },
  OBSERVED: { label: '观察', color: 'gold' },
  RISK: { label: '风险', color: 'orange' },
  HIGH_RISK: { label: '高危', color: 'red' },
}

function fmt(ts?: string) {
  if (!ts) return '-'
  return ts.replace('T', ' ').slice(0, 19)
}

function hours(seconds: number | undefined) {
  return `${((seconds ?? 0) / 3600).toFixed(1)} h`
}

function num(v: number | undefined, digits = 2) {
  return v === undefined || v === null ? '-' : v.toFixed(digits)
}

export default function BehaviorProfile() {
  const [searchParams] = useSearchParams()
  const [risk, setRisk] = useState<V52HighRiskPlayer[]>([])
  const [riskErr, setRiskErr] = useState('')
  const [loadingRisk, setLoadingRisk] = useState(false)
  const [pteid, setPteid] = useState('')
  const [selected, setSelected] = useState('')
  const [trendDays, setTrendDays] = useState(30)
  const [trend, setTrend] = useState<V53Trend | null>(null)
  const [profile, setProfile] = useState<V52BehaviorProfile | null>(null)
  const [reputation, setReputation] = useState<V52ReputationDetail | null>(null)
  const [player, setPlayer] = useState<AdminPlayerDetail | null>(null)
  const [detailErr, setDetailErr] = useState('')

  const riskRef = useScrollReveal<HTMLDivElement>()
  const { ref: devRef, reveal: revealDev } = useTableRowReveal<HTMLDivElement>({ x: -14 })
  const { ref: recRef, reveal: revealRec } = useTableRowReveal<HTMLDivElement>({ x: 14 })

  const loadRisk = useCallback(async () => {
    setLoadingRisk(true)
    try {
      const d = await api.v52.profile.highRisk(60)
      setRisk(d.players ?? [])
      setRiskErr('')
    } catch (e) {
      setRiskErr((e as Error).message)
    } finally {
      setLoadingRisk(false)
    }
  }, [])

  useEffect(() => {
    void loadRisk()
  }, [loadRisk])

  const loadDetail = useCallback(async (id: string) => {
    if (!id) return
    setSelected(id)
    setDetailErr('')
    const [p, r, d] = await Promise.allSettled([
      api.v52.profile.detail(id),
      api.v52.profile.reputation(id, 20),
      api.playerDetail(id),
    ])
    setProfile(p.status === 'fulfilled' ? p.value : null)
    setReputation(r.status === 'fulfilled' ? r.value : null)
    setPlayer(d.status === 'fulfilled' ? d.value : null)
    // 画像与信誉必得其一才算查询成功；全失败时给出首个错误
    if (p.status === 'rejected' && r.status === 'rejected') {
      setDetailErr((p.reason as Error)?.message || '画像查询失败')
    }
  }, [])

  // 趋势单独取，失败只影响趋势卡片，不拖垮整页
  useEffect(() => {
    if (!selected) {
      setTrend(null)
      return
    }
    let alive = true
    api.v53.trend(selected, trendDays)
      .then((t) => { if (alive) setTrend(t) })
      .catch(() => { if (alive) setTrend(null) })
    return () => { alive = false }
  }, [selected, trendDays])

  // 支持从硬件指纹页带 ?pteid= 直接跳进来查
  useEffect(() => {
    const fromQuery = searchParams.get('pteid')
    if (fromQuery) {
      setPteid(fromQuery)
      void loadDetail(fromQuery)
    }
  }, [searchParams, loadDetail])

  const trendOption = useMemo<EChartsOption>(() => {
    const daily = trend?.daily ?? []
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['在线时长(h)', '事件量'], right: 0, top: 0, textStyle: { fontSize: 11 } },
      grid: { left: 6, right: 6, top: 36, bottom: 2, containLabel: true },
      xAxis: {
        type: 'category',
        data: daily.map((p) => p.date.slice(5)),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      yAxis: [
        { type: 'value', name: 'h', splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } } },
        { type: 'value', name: '事件', splitLine: { show: false } },
      ],
      series: [
        {
          name: '在线时长(h)', type: 'line', smooth: true, symbol: 'none',
          areaStyle: { opacity: 0.12 }, data: daily.map((p) => p.online_hours),
        },
        {
          name: '事件量', type: 'bar', yAxisIndex: 1, barWidth: 5,
          itemStyle: { color: 'rgba(255,163,64,.65)' }, data: daily.map((p) => p.event_count),
        },
      ],
    }
  }, [trend])

  const hourlyOption = useMemo<EChartsOption>(() => {
    const hourly = trend?.hourly ?? []
    return {
      tooltip: { trigger: 'axis' },
      grid: { left: 6, right: 6, top: 18, bottom: 2, containLabel: true },
      xAxis: {
        type: 'category',
        data: hourly.map((h) => `${h.hour}`),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      yAxis: { type: 'value', name: 'h', splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } } },
      series: [{
        name: '在线时长(h)', type: 'bar', barWidth: 8, data: hourly.map((h) => h.online_hours),
        itemStyle: { color: 'rgba(64,150,255,.7)' },
      }],
    }
  }, [trend])

  useEffect(() => {
    const id = requestAnimationFrame(() => { revealDev(); revealRec() })
    return () => cancelAnimationFrame(id)
  }, [player, revealDev, revealRec])

  const deviceColumns: TableColumnsType<DeviceRecord> = [
    { title: '设备', dataIndex: 'deviceName', width: 150, render: (v: string) => v || '未命名设备' },
    { title: '平台', dataIndex: 'platform', width: 100, render: (v: string) => (v ? <Tag>{v}</Tag> : '-') },
    { title: '登录 IP', dataIndex: 'ip', width: 140, render: (v: string) => v || '-' },
    { title: '首次登录', dataIndex: 'firstLoginAt', width: 170, render: (v: string) => fmt(v) },
    { title: '最近登录', dataIndex: 'lastLoginAt', width: 170, render: (v: string) => fmt(v) },
    { title: '状态', dataIndex: 'active', width: 80, render: (v: boolean) => (v ? <Tag color="green">有效</Tag> : <Tag>停用</Tag>) },
  ]

  const recordColumns: TableColumnsType<CheatRecord> = [
    { title: '时间', dataIndex: 'occurredAt', width: 170, render: (v: string) => fmt(v) },
    { title: '作弊类型', dataIndex: 'cheatType', width: 130, render: (v: string) => <Tag color="volcano">{v}</Tag> },
    { title: '等级', dataIndex: 'level', width: 70 },
    { title: '风险分', dataIndex: 'riskScore', width: 80 },
    { title: '查端结论', dataIndex: 'inspectConclusion', render: (v: string) => v || '-' },
    { title: '状态', dataIndex: 'revoked', width: 90, render: (v: boolean) => (v ? <Tag>已撤销</Tag> : <Tag color="red">生效</Tag>) },
  ]

  const multiDevice = (player?.devices?.length ?? 0) > 1

  return (
    <div>
      <PageHeader
        title="行为画像"
        description="个人行为基线、自适应阈值与设备/检测历史（v5.2 §6.2）"
        extra={
          <Space>
            <Input
              placeholder="输入 PTEID 精确查询"
              value={pteid}
              onChange={(e) => setPteid(e.target.value)}
              onPressEnter={() => void loadDetail(pteid.trim())}
              style={{ width: 220 }}
              allowClear
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={() => void loadDetail(pteid.trim())}>查询</Button>
            <Button icon={<ReloadOutlined />} loading={loadingRisk} onClick={() => void loadRisk()}>刷新风险池</Button>
          </Space>
        }
      />

      <Row gutter={[15, 15]}>
        <Col xs={24} lg={7}>
          <Card
            title="风险池"
            className="pacc-glass-md"
            extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>信誉 &lt; 500 · {risk.length} 人</span>}
            styles={{ body: { padding: risk.length ? '4px 0' : 20 } }}
          >
            <div ref={riskRef}>
              {riskErr && <Alert type="error" showIcon message={riskErr} style={{ margin: 12 }} />}
              {risk.length === 0 && !riskErr ? (
                <Empty description="暂无风险玩家" />
              ) : (
                <List
                  loading={loadingRisk}
                  dataSource={risk}
                  style={{ maxHeight: 560, overflow: 'auto' }}
                  renderItem={(p) => (
                    <List.Item
                      onClick={() => void loadDetail(p.pteid)}
                      style={{
                        cursor: 'pointer',
                        padding: '9px 14px',
                        background: selected === p.pteid ? 'rgba(255,255,255,.045)' : undefined,
                        borderInlineStart: selected === p.pteid ? '2px solid #4096ff' : '2px solid transparent',
                      }}
                    >
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontFamily: 'monospace', fontSize: 12.5 }}>{p.pteid}</div>
                        <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 3 }}>
                          误报 {p.false_positives} · 更新 {fmt(p.updated_at)}
                        </div>
                      </div>
                      <Space size={6}>
                        <Tag color={LEVEL_META[p.reputation_level]?.color ?? 'default'}>
                          {LEVEL_META[p.reputation_level]?.label ?? p.reputation_level}
                        </Tag>
                        <span style={{ fontWeight: 700, fontSize: 13 }}>{p.reputation_score}</span>
                      </Space>
                    </List.Item>
                  )}
                />
              )}
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={17}>
          {detailErr && <Alert type="error" showIcon message={detailErr} style={{ marginBottom: 14 }} closable onClose={() => setDetailErr('')} />}
          {!selected ? (
            <Card className="pacc-glass-md" styles={{ body: { padding: 60 } }}>
              <Empty description="从左侧风险池选择玩家，或按 PTEID 精确查询" />
            </Card>
          ) : (
            <Space direction="vertical" size={15} style={{ width: '100%' }}>
              <Card
                title="画像概览"
                className="pacc-glass-lg"
                extra={<span style={{ fontFamily: 'monospace', fontSize: 12.5 }}>{selected}</span>}
              >
                {!profile?.exists ? (
                  <Alert
                    type="warning"
                    showIcon
                    message="该玩家尚无行为画像"
                    description="画像行在客户端首次上报特征向量后生成；此时检测阈值按新玩家倍率收紧。"
                  />
                ) : (
                  <>
                    <Descriptions
                      size="small"
                      column={{ xs: 1, sm: 2, lg: 3 }}
                      items={[
                        { key: 'cps', label: '平均 CPS', children: num(profile.mean_cps) },
                        { key: 'cps_std', label: 'CPS 标准差', children: num(profile.cps_std) },
                        { key: 'aim', label: '瞄准平滑度', children: num(profile.mean_aim_smoothness) },
                        { key: 'aim_std', label: '平滑度标准差', children: num(profile.aim_smoothness_std) },
                        { key: 'speed', label: '移动速度比', children: num(profile.mean_speed, 3) },
                        { key: 'speed_std', label: '速度标准差', children: num(profile.speed_std, 3) },
                        { key: 'obs', label: '观测时长', children: hours(profile.observed_seconds) },
                        { key: 'samples', label: '样本数', children: profile.sample_count },
                        { key: 'sessions', label: '会话数', children: profile.total_sessions ?? 0 },
                        { key: 'det', label: '累计检测', children: profile.total_detections ?? 0 },
                        { key: 'fp', label: '误报次数', children: profile.false_positives ?? 0 },
                        { key: 'ver', label: '画像版本', children: `v${profile.profile_version ?? 1}` },
                        { key: 'first', label: '首次观测', children: fmt(profile.first_seen_at) },
                        { key: 'updated', label: '最近更新', children: fmt(profile.updated_at) },
                      ]}
                    />
                    <div style={{ display: 'flex', gap: 30, flexWrap: 'wrap', marginTop: 14, alignItems: 'center' }}>
                      <div style={{ minWidth: 190 }}>
                        <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>画像稳定度（10h 观测收敛）</div>
                        <Progress percent={Math.round((profile.stability ?? 0) * 100)} size="small" />
                      </div>
                      <Tooltip title="新玩家更敏感、零误报老玩家更宽松；设备指纹突变时收紧">
                        <div>
                          <div style={{ fontSize: 12, color: 'var(--muted)' }}>当前自适应阈值倍率</div>
                          <div style={{ fontSize: 20, fontWeight: 700, color: profile.adaptive_multiplier < 1 ? '#ffa940' : '#52c41a' }}>
                            ×{(profile.adaptive_multiplier ?? 1).toFixed(2)}
                          </div>
                        </div>
                      </Tooltip>
                      {(profile.fingerprint_mutation || multiDevice) && (
                        <Tag color="red">设备指纹突变（过手多枚指纹，画像收紧）</Tag>
                      )}
                    </div>
                  </>
                )}
              </Card>

              <Card
                title="行为趋势"
                className="pacc-glass-md"
                extra={
                  <Space size={8}>
                    {trend?.baseline.reputation_level && (
                      <Tag color={LEVEL_META[trend.baseline.reputation_level]?.color ?? 'default'}>
                        {LEVEL_META[trend.baseline.reputation_level]?.label ?? trend.baseline.reputation_level}
                      </Tag>
                    )}
                    <Select
                      size="small"
                      value={trendDays}
                      onChange={setTrendDays}
                      style={{ width: 98 }}
                      options={[
                        { label: '近 7 天', value: 7 },
                        { label: '近 30 天', value: 30 },
                        { label: '近 90 天', value: 90 },
                      ]}
                    />
                  </Space>
                }
              >
                {trend && trend.daily.length > 0 ? (
                  <>
                    <EChart option={trendOption} height={210} />
                    <div style={{ marginTop: 6 }}>
                      <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 2 }}>
                        时段分布（窗口内每个小时累计在线 {hours(trend.hourly.reduce((s, h) => s + h.session_seconds, 0))}）
                      </div>
                      <EChart option={hourlyOption} height={132} />
                    </div>
                  </>
                ) : (
                  <Empty description="该窗口内没有作息明细" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                )}
                <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 10, lineHeight: 1.7 }}>
                  折线为每日在线时长、柱形为当日事件量，来自逐日作息明细；时段分布是各小时累计值，用来看出作息集中在哪些时段。
                  CPS、瞄准平滑度、移动速度只存聚合基线值（
                  {trend?.baseline.exists
                    ? `均值 CPS ${num(trend.baseline.mean_cps)}、样本 ${trend.baseline.sample_count ?? 0}`
                    : '该玩家暂无基线'}
                  ），后端没有逐日序列，不能当作走势。
                </div>
              </Card>

              {reputation && (
                <Card
                  title="信誉与检测策略"
                  className="pacc-glass-md"
                  extra={
                    <Space size={8}>
                      <Tag color={LEVEL_META[reputation.level]?.color ?? 'default'}>
                        {LEVEL_META[reputation.level]?.label ?? reputation.level}
                      </Tag>
                      <span style={{ fontWeight: 700 }}>{reputation.score} / {reputation.max_score}</span>
                    </Space>
                  }
                >
                  <Descriptions
                    size="small"
                    column={{ xs: 1, sm: 2, lg: 3 }}
                    items={[
                      { key: 'delta', label: '阈值调整', children: `${reputation.policy.threshold_percent_delta > 0 ? '+' : ''}${reputation.policy.threshold_percent_delta}%（×${reputation.policy.threshold_multiplier.toFixed(2)}）` },
                      { key: 'l0', label: '仅 L0 规则', children: reputation.policy.l0_only ? '是' : '否' },
                      { key: 'l2', label: '强制 L2 深检', children: reputation.policy.force_l2 ? '是' : '否' },
                      { key: 'full', label: '每局全量上报特征', children: reputation.policy.full_feature_report ? '是' : '否' },
                      { key: 'legacy', label: '兼容 0-100 口径', children: reputation.legacy_scale_score },
                    ]}
                  />
                  <Table
                    rowKey={(r) => `${r.created_at}-${r.source}`}
                    size="small"
                    style={{ marginTop: 12 }}
                    pagination={false}
                    scroll={{ y: 220 }}
                    dataSource={reputation.logs}
                    locale={{ emptyText: '暂无 v5.2 口径信誉审计' }}
                    columns={[
                      { title: '时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
                      {
                        title: '变动', dataIndex: 'delta', width: 80,
                        render: (v: number) => <span style={{ color: v >= 0 ? '#52c41a' : '#ff4d3d' }}>{v > 0 ? `+${v}` : v}</span>,
                      },
                      { title: '变动后', dataIndex: 'score_after', width: 80 },
                      { title: '原因', dataIndex: 'reason' },
                    ]}
                  />
                </Card>
              )}

              <Row gutter={[15, 15]}>
                <Col xs={24} xl={12}>
                  <Card title="设备与登录" className="pacc-glass-md" extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>{player?.devices?.length ?? 0} 台</span>}>
                    <div ref={devRef}>
                      <Table<DeviceRecord>
                        rowKey="deviceId"
                        size="small"
                        columns={deviceColumns}
                        dataSource={player?.devices ?? []}
                        pagination={false}
                        scroll={{ x: 700 }}
                        locale={{ emptyText: '无设备记录' }}
                      />
                    </div>
                  </Card>
                </Col>
                <Col xs={24} xl={12}>
                  <Card
                    title="检测历史"
                    className="pacc-glass-md"
                    extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>红屏 {player?.totalRedscreen ?? 0} 次</span>}
                  >
                    <div ref={recRef}>
                      <Table<CheatRecord>
                        rowKey="recordId"
                        size="small"
                        columns={recordColumns}
                        dataSource={player?.records ?? []}
                        pagination={{ pageSize: 5, showSizeChanger: false }}
                        scroll={{ x: 700 }}
                        locale={{ emptyText: '无作弊记录' }}
                      />
                    </div>
                  </Card>
                </Col>
              </Row>
            </Space>
          )}
        </Col>
      </Row>
    </div>
  )
}