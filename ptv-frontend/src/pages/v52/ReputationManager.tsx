import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Col, Descriptions, Empty, Form, Input, InputNumber, Row, Space, Table, Tag, message,
} from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import type { EChartsOption } from 'echarts'
import PageHeader from '../../components/PageHeader'
import MetricCard from '../../components/MetricCard'
import EChart from '../../components/EChart'
import { api } from '../../api/client'
import type { V52HighRiskPlayer, V52ReputationDetail, V53ReputationOverview } from '../../types'
import { useScrollReveal, useTableRowReveal } from '../../hooks/useGSAP'

const LEVEL_META: Record<string, { label: string; color: string }> = {
  TRUSTED: { label: '信任', color: 'green' },
  NORMAL: { label: '正常', color: 'cyan' },
  OBSERVED: { label: '观察', color: 'gold' },
  RISK: { label: '风险', color: 'orange' },
  HIGH_RISK: { label: '高危', color: 'red' },
}

const BUCKET_COLOR: Record<string, string> = {
  HIGH_RISK: '#ff4d3d',
  RISK: '#ffa940',
  OBSERVED: '#ffd666',
  NORMAL: '#36cfc9',
  TRUSTED: '#52c41a',
}

/** 把后端下发的等级策略转成一句人话。 */
function policyText(p: V53ReputationOverview['buckets'][number]['sample_policy']) {
  const bits = [
    p.threshold_percent_delta === 0
      ? '标准阈值'
      : `阈值${p.threshold_percent_delta > 0 ? '放宽' : '收紧'} ${Math.abs(p.threshold_percent_delta)}%`,
  ]
  if (p.l0_only) bits.push('仅执行 L0 规则')
  if (p.force_l2) bits.push('强制 L2 深度检测')
  if (p.full_feature_report) bits.push('每局全量上报特征')
  return bits.join('，')
}

function fmt(ts: string) {
  return ts ? ts.replace('T', ' ').slice(0, 19) : '-'
}

export default function ReputationManager() {
  const [risk, setRisk] = useState<V52HighRiskPlayer[]>([])
  const [loadingRisk, setLoadingRisk] = useState(false)
  const [riskErr, setRiskErr] = useState('')
  const [pteid, setPteid] = useState('')
  const [overview, setOverview] = useState<V53ReputationOverview | null>(null)
  const [detail, setDetail] = useState<V52ReputationDetail | null>(null)
  const [queryErr, setQueryErr] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ delta: number; reason: string }>()

  const chartRef = useScrollReveal<HTMLDivElement>()
  const { ref: logRef, reveal: revealLog } = useTableRowReveal<HTMLDivElement>({ x: -14 })

  const loadRisk = useCallback(async () => {
    setLoadingRisk(true)
    try {
      const d = await api.v52.profile.highRisk(200)
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

  // 全量分布：一次拿全服各等级人数，失败只影响右侧分布卡片
  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.v53.reputationOverview())
    } catch {
      setOverview(null)
    }
  }, [])

  useEffect(() => {
    void loadOverview()
  }, [loadOverview])

  const buckets = useMemo(() => {
    const high = risk.filter((p) => p.reputation_score < 300).length
    return { high, risk: risk.length - high }
  }, [risk])

  const chartOption = useMemo<EChartsOption>(() => {
    const list = overview?.buckets ?? []
    return {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const p = (params as { dataIndex: number }[])[0]
          const b = list[p.dataIndex]
          if (!b) return ''
          return `${LEVEL_META[b.level]?.label ?? b.level}（${b.min_score}-${b.max_score}）<br/>`
            + `${b.count} 人 · 占比 ${(b.share * 100).toFixed(1)}%<br/>平均分 ${b.average_score}`
        },
      },
      grid: { left: 8, right: 16, top: 24, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category',
        data: list.map((b) => LEVEL_META[b.level]?.label ?? b.level),
        axisLine: { lineStyle: { color: 'rgba(255,255,255,.13)' } },
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(255,255,255,.06)' } } },
      series: [{
        type: 'bar',
        barWidth: 30,
        data: list.map((b) => ({ value: b.count, itemStyle: { color: BUCKET_COLOR[b.level] ?? '#8c8c8c' } })),
        label: { show: true, position: 'top', color: 'var(--muted)', fontSize: 11 },
      }],
    }
  }, [overview])

  useEffect(() => {
    const id = requestAnimationFrame(revealLog)
    return () => cancelAnimationFrame(id)
  }, [detail, revealLog])

  const loadDetail = useCallback(async (id: string) => {
    if (!id) return
    try {
      const d = await api.v52.profile.reputation(id, 50)
      setDetail(d)
      setQueryErr('')
    } catch (e) {
      setDetail(null)
      setQueryErr((e as Error).message)
    }
  }, [])

  async function submitAdjust() {
    if (!detail) return
    const v = await form.validateFields()
    setSubmitting(true)
    try {
      const r = await api.v52.profile.adjust(detail.pteid, v.delta, v.reason)
      setDetail(r.reputation)
      message.success(r.applied ? `已调整 ${v.delta > 0 ? '+' : ''}${v.delta} 分` : '分值已在上限/下限，未记账')
      form.resetFields()
      await loadRisk()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="信誉管理"
        description="0-1000 分制信誉 v2：分值 / 等级检测策略 / 人工调整（全程审计）"
        error={queryErr}
        onCloseError={() => setQueryErr('')}
        extra={
          <Space>
            <Input
              placeholder="输入 PTEID 查询信誉"
              value={pteid}
              onChange={(e) => setPteid(e.target.value)}
              onPressEnter={() => void loadDetail(pteid.trim())}
              style={{ width: 220 }}
              allowClear
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={() => void loadDetail(pteid.trim())}>查询</Button>
          </Space>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 14, marginBottom: 15 }}>
        <MetricCard label="全服画像玩家" value={overview?.total ?? '-'} hint="已生成行为画像的玩家" />
        <MetricCard
          label="平均信誉分"
          value={overview?.average_score ?? '-'}
          accent="#4096ff"
          hint={overview ? `最低 ${overview.lowest_score} · 最高 ${overview.highest_score}` : '按画像全量统计'}
        />
        <MetricCard label="高危玩家" value={overview?.buckets.find((b) => b.level === 'HIGH_RISK')?.count ?? buckets.high} accent="#ff4d3d" hint="信誉分 < 300" />
        <MetricCard label="风险玩家" value={overview?.buckets.find((b) => b.level === 'RISK')?.count ?? buckets.risk} accent="#ffa940" hint="信誉分 300 - 499" />
      </div>

      <Row gutter={[15, 15]}>
        <Col xs={24} xl={15}>
          {!detail ? (
            <Card className="pacc-glass-md" styles={{ body: { padding: 56 } }}>
              <Empty description="按 PTEID 查询玩家信誉，或从右侧风险池选择" />
            </Card>
          ) : (
            <Space direction="vertical" size={15} style={{ width: '100%' }}>
              <Card
                title="信誉详情"
                className="pacc-glass-lg"
                extra={
                  <Space size={8}>
                    <Tag color={LEVEL_META[detail.level]?.color ?? 'default'}>
                      {LEVEL_META[detail.level]?.label ?? detail.level}
                    </Tag>
                    <span style={{ fontFamily: 'monospace', fontSize: 12.5 }}>{detail.pteid}</span>
                  </Space>
                }
              >
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 12 }}>
                  <span style={{ fontSize: 34, fontWeight: 700, letterSpacing: '-.02em' }}>{detail.score}</span>
                  <span style={{ color: 'var(--muted)', fontSize: 13 }}>/ {detail.max_score} · 兼容 0-100 口径 {detail.legacy_scale_score}</span>
                </div>
                <Descriptions
                  size="small"
                  column={{ xs: 1, sm: 2 }}
                  items={[
                    { key: 'delta', label: '阈值调整', children: `${detail.policy.threshold_percent_delta > 0 ? '+' : ''}${detail.policy.threshold_percent_delta}%（×${detail.policy.threshold_multiplier.toFixed(2)}）` },
                    { key: 'l0', label: '仅 L0 规则', children: detail.policy.l0_only ? '是' : '否' },
                    { key: 'l2', label: '强制 L2 深检', children: detail.policy.force_l2 ? '是' : '否' },
                    { key: 'full', label: '每局全量上报特征', children: detail.policy.full_feature_report ? '是' : '否' },
                  ]}
                />
              </Card>

              <Card title="人工调整" className="pacc-glass-md">
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 13 }}
                  message="调整必须填写原因，操作人取自管理端会话，全程写入审计且不可删除。"
                />
                <Form form={form} layout="inline" style={{ gap: 11, rowGap: 11 }}>
                  <Form.Item
                    label="调整分值"
                    name="delta"
                    rules={[{ required: true, message: '请填写调整量' }]}
                    tooltip="可正可负，0-1000 口径，超出上下限会被裁剪"
                  >
                    <InputNumber min={-1000} max={1000} step={5} style={{ width: 130 }} />
                  </Form.Item>
                  <Form.Item
                    label="原因"
                    name="reason"
                    rules={[{ required: true, min: 2, message: '原因至少 2 个字符' }]}
                    style={{ flex: '1 1 260px', minWidth: 220 }}
                  >
                    <Input placeholder="如：人工复核确认误报，补回分值" />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" loading={submitting} onClick={() => void submitAdjust()}>提交调整</Button>
                  </Form.Item>
                </Form>
              </Card>

              <Card title="信誉审计明细" className="pacc-glass-md" extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>仅 v5.2 口径（0-1000）</span>}>
                <div ref={logRef}>
                  <Table
                    rowKey={(r) => `${r.created_at}-${r.source}`}
                    size="small"
                    pagination={{ pageSize: 8, showSizeChanger: false }}
                    dataSource={detail.logs}
                    locale={{ emptyText: '暂无审计记录' }}
                    columns={[
                      { title: '时间', dataIndex: 'created_at', width: 170, render: (v: string) => fmt(v) },
                      {
                        title: '变动', dataIndex: 'delta', width: 80,
                        render: (v: number) => <span style={{ color: v >= 0 ? '#52c41a' : '#ff4d3d' }}>{v > 0 ? `+${v}` : v}</span>,
                      },
                      { title: '变动后', dataIndex: 'score_after', width: 80 },
                      { title: '原因', dataIndex: 'reason' },
                      { title: '来源', dataIndex: 'source', width: 220, render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 11.5 }}>{v}</span> },
                    ]}
                  />
                </div>
              </Card>
            </Space>
          )}
        </Col>

        <Col xs={24} xl={9}>
          <Space direction="vertical" size={15} style={{ width: '100%' }}>
            <Card title="信誉分布" className="pacc-glass-md" extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>全服 {overview?.total ?? 0} 人</span>}>
              <div ref={chartRef}>
                <EChart option={chartOption} height={190} />
              </div>
              <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 6 }}>
                全量画像按等级分段，鼠标悬停看各段人数、占比与平均分。
              </div>
            </Card>

            <Card title="风险池玩家" className="pacc-glass-md" styles={{ body: { padding: 0 } }}>
              {riskErr && <Alert type="error" showIcon message={riskErr} style={{ margin: 12 }} />}
              <Table<V52HighRiskPlayer>
                rowKey="pteid"
                size="small"
                loading={loadingRisk}
                dataSource={risk}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                onRow={(r) => ({ onClick: () => { setPteid(r.pteid); void loadDetail(r.pteid) }, style: { cursor: 'pointer' } })}
                locale={{ emptyText: '暂无风险玩家' }}
                columns={[
                  { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
                  { title: '分值', dataIndex: 'reputation_score', width: 70 },
                  {
                    title: '等级', dataIndex: 'reputation_level', width: 84,
                    render: (v: string) => <Tag color={LEVEL_META[v]?.color ?? 'default'}>{LEVEL_META[v]?.label ?? v}</Tag>,
                  },
                ]}
              />
            </Card>

            <Card title="信誉规则" className="pacc-glass-md" extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>后端下发</span>}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '7px 0', borderBottom: '1px solid var(--border)', fontSize: 12.5 }}>
                <span style={{ color: 'var(--muted)' }}>初始分</span>
                <span>{overview ? `${overview.initial_score}（区间 ${overview.min_score} - ${overview.max_score}）` : '600'}</span>
              </div>
              {(overview?.rules ?? []).map((r) => (
                <div key={r.event} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '7px 0', borderBottom: '1px solid var(--border)', fontSize: 12.5 }}>
                  <span style={{ color: 'var(--muted)' }}>{r.label}</span>
                  <span style={{ color: r.delta >= 0 ? '#52c41a' : '#ff4d3d' }}>{r.delta > 0 ? `+${r.delta}` : r.delta}</span>
                </div>
              ))}
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '7px 0', fontSize: 12.5 }}>
                <span style={{ color: 'var(--muted)' }}>无检测小时奖励上限</span>
                <span>+{overview?.clean_hour_bonus_cap ?? 200}</span>
              </div>

              <div style={{ marginTop: 12 }}>
                {(overview?.buckets ?? []).slice().reverse().map((b) => (
                  <div key={b.level} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '5px 0', fontSize: 12.5 }}>
                    <Tag color={LEVEL_META[b.level]?.color ?? 'default'} style={{ marginInlineEnd: 0 }}>
                      {LEVEL_META[b.level]?.label ?? b.level}
                    </Tag>
                    <span style={{ color: 'var(--muted)', minWidth: 78 }}>{b.min_score} - {b.max_score}</span>
                    <span>{policyText(b.sample_policy)}</span>
                  </div>
                ))}
              </div>
              <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 10 }}>
                等级阈值与各段策略均由后端常量推导（900 / 700 / 500 / 300），当前不支持在线配置。
              </div>
            </Card>
          </Space>
        </Col>
      </Row>
    </div>
  )
}