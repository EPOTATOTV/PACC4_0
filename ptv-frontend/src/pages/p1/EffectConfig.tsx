import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Col, Input, Row, Segmented, Space, Switch, Table, Tag, message } from 'antd'
import type { TableColumnsType } from 'antd'
import type { EChartsOption } from 'echarts'
import { ReloadOutlined, SaveOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { DlRelease, DlStats, EffectConfigAuditRow, EffectConfigDto } from '../../types'
import EChart from '../../components/EChart'
import { RedScreenOverlay } from '../../components/animations/RedScreenOverlay'

/** 平台折线配色：与 Dashboard 图表同一套，避免两处图表观感割裂 */
const DL_PALETTE = ['#58a6ff', '#d29922', '#3fb950', '#a371f7', '#39c5cf', '#ff3b30']

const LEVEL_OPTIONS = [
  { label: '关闭', value: 'off' },
  { label: '温和', value: 'gentle' },
  { label: '标准', value: 'standard' },
  { label: '强烈', value: 'strong' },
]

const EFFECT_KEYS: { key: string; label: string }[] = [
  { key: 'page_transition', label: '页面转场' },
  { key: 'counter', label: '数字滚动' },
  { key: 'table_reveal', label: '表格行入场' },
  { key: 'modal_reveal', label: '弹层入场' },
  { key: 'data_stream', label: '实时数据流' },
  { key: 'cursor_glow', label: '指针光斑' },
]

/** 动效配置中心 + 红屏模板 + 下载站统计。 */
export default function EffectConfig() {
  const [cfg, setCfg] = useState<EffectConfigDto | null>(null)
  const [effects, setEffects] = useState<Record<string, boolean>>({})
  const [redscreen, setRedscreen] = useState<Record<string, unknown>>({})
  const [err, setErr] = useState('')
  const [saving, setSaving] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)

  const [releases, setReleases] = useState<DlRelease[]>([])
  const [stats, setStats] = useState<DlStats | null>(null)
  const [statsDays, setStatsDays] = useState(14)
  const [history, setHistory] = useState<EffectConfigAuditRow[]>([])

  const load = useCallback(async () => {
    try {
      const [c, rel, st, his] = await Promise.all([
        api.effect.get(),
        api.dl.releases().catch(() => [] as DlRelease[]),
        api.dl.stats(statsDays).catch(() => null),
        api.effect.history().catch(() => [] as EffectConfigAuditRow[]),
      ])
      setCfg(c)
      setEffects(parseBool(c.effects_json))
      setRedscreen(parseObj(c.redscreen_json))
      setReleases(rel)
      setStats(st)
      setHistory(his)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [statsDays])

  useEffect(() => {
    void load()
  }, [load])

  async function save() {
    setSaving(true)
    try {
      const saved = await api.effect.update({
        motion_level: cfg?.motion_level,
        redscreen_template: cfg?.redscreen_template,
        effects_json: JSON.stringify(effects),
        redscreen_json: JSON.stringify(redscreen),
        updated_by: 'admin',
      })
      setCfg(saved)
      setEffects(parseBool(saved.effects_json))
      setRedscreen(parseObj(saved.redscreen_json))
      message.success('动效配置已保存')
      // 保存后立刻刷新变更历史，让刚落的这条记录出现在列表首位
      void load()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const platformSum = useMemo(() => {
    const m: Record<string, number> = {}
    if (stats) {
      for (const [k, v] of Object.entries(stats.platform)) m[k] = v
    }
    return m
  }, [stats])

  // 后端已把各平台序列对齐到同一条日期轴（缺数据补 0），这里只做展示映射
  const dlOption: EChartsOption = useMemo(() => {
    const dates = stats?.dates ?? []
    const series = stats?.series ?? []
    const multi = series.length > 1
    return {
      tooltip: { trigger: 'axis' },
      legend: multi ? { bottom: 0, type: 'scroll', textStyle: { color: '#8b949e' } } : undefined,
      grid: { left: 40, right: 16, top: 18, bottom: multi ? 48 : 28 },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates.map((d) => d.slice(5)),
        axisLine: { lineStyle: { color: '#8b949e' } },
        axisLabel: { color: '#8b949e' },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(139,148,158,0.15)' } },
        axisLabel: { color: '#8b949e' },
      },
      series: series.map((s, i) => ({
        name: s.platform,
        type: 'line',
        smooth: true,
        symbolSize: 5,
        data: s.data,
        lineStyle: { color: DL_PALETTE[i % DL_PALETTE.length], width: 2 },
        itemStyle: { color: DL_PALETTE[i % DL_PALETTE.length] },
      })),
    }
  }, [stats])

  const hasDlTrend = (stats?.series ?? []).some((s) => s.data.some((v) => v > 0))

  const statColumns: TableColumnsType<{ platform: string; count: number }> = [
    { title: '平台', dataIndex: 'platform', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: '下载次数', dataIndex: 'count', width: 140 },
  ]

  const statData = Object.entries(platformSum).map(([platform, count]) => ({ platform, count }))

  const releaseColumns: TableColumnsType<DlRelease> = [
    { title: '平台', dataIndex: 'platform', width: 90 },
    { title: '产物', dataIndex: 'artifact', width: 90 },
    { title: '版本', dataIndex: 'version', width: 90 },
    { title: '文件', dataIndex: 'fileUrl', ellipsis: true },
    { title: 'SHA-256', dataIndex: 'sha256', ellipsis: true },
    { title: '启用', dataIndex: 'enabled', width: 70, render: (v: boolean) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>) },
  ]

  // 摘要由服务端算好（含改动前后的值），前端只负责展示，避免两边 diff 逻辑不一致
  const auditColumns: TableColumnsType<EffectConfigAuditRow> = [
    {
      title: '时间', dataIndex: 'createdAt', width: 168,
      render: (v: number) => <span className="mono">{new Date(v).toLocaleString('zh-CN', { hour12: false })}</span>,
    },
    { title: '操作人', dataIndex: 'changedBy', width: 140 },
    { title: '档位', dataIndex: 'motionLevel', width: 96, render: (v: string) => <Tag>{v}</Tag> },
    { title: '变更内容', dataIndex: 'summary', ellipsis: true },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: 18 }}>动效配置中心</h3>
        <div style={{ flex: 1 }} />
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={12}>
          <Card title={<Space><ThunderboltOutlined />全局动效档位</Space>} size="small">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <div style={{ marginBottom: 6, fontSize: 13, color: 'var(--muted)' }}>动效档位</div>
                <Segmented
                  value={cfg?.motion_level}
                  options={LEVEL_OPTIONS}
                  onChange={(v) => setCfg((c) => (c ? { ...c, motion_level: String(v) } : c))}
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                {EFFECT_KEYS.map((it) => (
                  <div key={it.key} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 13 }}>{it.label}</span>
                    <Switch size="small" checked={effects[it.key] ?? true} onChange={(v) => setEffects((m) => ({ ...m, [it.key]: v }))} />
                  </div>
                ))}
              </div>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            title={<Space><ThunderboltOutlined />红屏动效模板</Space>}
            size="small"
            extra={<Button size="small" onClick={() => setPreviewOpen(true)}>预览红屏</Button>}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <div style={{ marginBottom: 6, fontSize: 13, color: 'var(--muted)' }}>模板</div>
                <Segmented
                  value={cfg?.redscreen_template}
                  options={LEVEL_OPTIONS}
                  onChange={(v) => setCfg((c) => (c ? { ...c, redscreen_template: String(v) } : c))}
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                {[
                  { key: 'flash_count', label: '闪烁次数', type: 'number' },
                  { key: 'expand', label: '中心扩散', type: 'bool' },
                  { key: 'scanline', label: '扫描线', type: 'bool' },
                  { key: 'glitch_text', label: '故障文字', type: 'bool' },
                  { key: 'shake', label: '屏幕震动', type: 'bool' },
                ].map((it) => (
                  <div key={it.key} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 13 }}>{it.label}</span>
                    {it.type === 'bool' ? (
                      <Switch size="small" checked={Boolean(redscreen[it.key])} onChange={(v) => setRedscreen((m) => ({ ...m, [it.key]: v }))} />
                    ) : (
                      <Input
                        type="number"
                        size="small"
                        style={{ width: 80 }}
                        value={Number(redscreen[it.key]) || 0}
                        min={0}
                        max={10}
                        onChange={(e) => setRedscreen((m) => ({ ...m, [it.key]: Number(e.target.value) }))}
                      />
                    )}
                  </div>
                ))}
              </div>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="下载统计" size="small" extra={
            <Segmented size="small" value={statsDays} options={[{ label: '7 天', value: 7 }, { label: '14 天', value: 14 }, { label: '30 天', value: 30 }]} onChange={(v) => setStatsDays(Number(v))} />
          }>
            <EChart option={dlOption} height={220} />
            {!hasDlTrend && (
              <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--muted)', margin: '2px 0 12px' }}>
                近 {statsDays} 天暂无下载数据
              </div>
            )}
            <Table<{ platform: string; count: number }> rowKey="platform" size="small" columns={statColumns} dataSource={statData} pagination={false} locale={{ emptyText: '暂无下载数据' }} />
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="当前发布物" size="small">
            <Table<DlRelease> rowKey="id" size="small" columns={releaseColumns} dataSource={releases} pagination={false} scroll={{ x: 560 }} locale={{ emptyText: '暂无发布物' }} />
          </Card>
        </Col>

        <Col xs={24}>
          <Card
            title="变更历史"
            size="small"
            extra={<span style={{ fontSize: 12, color: 'var(--muted)' }}>最近 20 条</span>}
          >
            <Table<EffectConfigAuditRow>
              rowKey="id"
              size="small"
              columns={auditColumns}
              dataSource={history}
              pagination={false}
              scroll={{ x: 720 }}
              locale={{ emptyText: '暂无变更记录' }}
            />
          </Card>
        </Col>
      </Row>

      <RedScreenOverlay
        open={previewOpen}
        onClose={() => setPreviewOpen(false)}
        title="检测到作弊行为"
        stamp="CHEAT DETECTED"
        details={[
          { label: '风险等级', value: 'HIGH' },
          { label: '作弊类型', value: 'MEMORY_WRITE' },
        ]}
      />
    </div>
  )
}

function parseBool(json: string): Record<string, boolean> {
  const o = parseObj(json)
  const out: Record<string, boolean> = {}
  for (const [k, v] of Object.entries(o)) out[k] = Boolean(v)
  return out
}

function parseObj(json: string): Record<string, unknown> {
  try {
    return JSON.parse(json || '{}') as Record<string, unknown>
  } catch {
    return {}
  }
}