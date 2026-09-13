import { useCallback, useEffect, useState } from 'react'
import { Alert, Card, Col, Row, Statistic, Tag } from 'antd'
import { CheckCircleOutlined, CrownOutlined, SafetyOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ReputationSummary } from '../../types'
import EChart from '../../components/EChart'
import type { EChartsOption } from 'echarts'

const TIER_META: Record<string, { color: string; label: string }> = {
  TRUSTED: { color: 'green', label: '可信玩家' },
  NORMAL: { color: 'blue', label: '标准' },
  MONITORED: { color: 'orange', label: '需关注' },
  RESTRICTED: { color: 'red', label: '受限' },
}

export default function PlayerReputation() {
  const [data, setData] = useState<ReputationSummary | null>(null)
  const [err, setErr] = useState('')

  const load = useCallback(async () => {
    try {
      setData(await api.reputation.summary())
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const tier = data?.tier ?? 'NORMAL'
  const meta = TIER_META[tier] ?? TIER_META.NORMAL

  const trendOption: EChartsOption = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: (data?.trend ?? []).map((p) => p.date.slice(5)), axisLabel: { color: '#98a0ae' }, axisLine: { lineStyle: { color: '#2a2f3a' } } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#98a0ae' }, splitLine: { lineStyle: { color: 'rgba(255,255,255,.05)' } } },
    series: [
      {
        type: 'line',
        smooth: true,
        data: (data?.trend ?? []).map((p) => p.score),
        areaStyle: { color: 'rgba(255,107,94,.18)' },
        lineStyle: { color: '#ff6b5e', width: 2 },
        itemStyle: { color: '#ff6b5e' },
      },
    ],
  }

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h3 style={{ margin: 0, fontSize: 20 }}>我的信誉分</h3>
        {err && <Alert type="error" showIcon message={err} style={{ marginTop: 8 }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="信誉分" value={data?.score ?? 100} valueStyle={{ fontSize: 44, fontWeight: 700 }} />
            <div style={{ marginTop: 8 }}>
              <Tag color={meta.color}>{meta.label}</Tag>
            </div>
          </Card>
        </Col>
        <Col xs={24} md={16}>
          <Card title={<span><CrownOutlined /> 当前等级权益</span>}>
            {(data?.equities?.length ? data.equities : ['标准权益']).map((eq) => (
              <div key={eq} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0' }}>
                <CheckCircleOutlined style={{ color: '#3fb68b' }} />
                <span>{eq}</span>
              </div>
            ))}
          </Card>
        </Col>
      </Row>

      <Card title={<span><SafetyOutlined /> 近 30 天信誉趋势</span>} style={{ marginTop: 16 }}>
        {(data?.trend?.length ?? 0) > 0
          ? <EChart option={trendOption} height={280} />
          : <div style={{ color: 'var(--muted)', padding: 24, textAlign: 'center' }}>暂无趋势数据</div>}
      </Card>
    </div>
  )
}